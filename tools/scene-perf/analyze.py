#!/usr/bin/env python3
"""Analyze a rendered-scene capture; requires numpy and Pillow. No game connection."""
import argparse
import csv
import json
from pathlib import Path
import numpy as np
from PIL import Image

p = argparse.ArgumentParser()
p.add_argument('capture', type=Path)
p.add_argument('--reference', type=Path, help='Check that every in-radius pair also exists in this replay/capture')
p.add_argument('--stride', type=int, default=1, help='1 = every pixel; larger values are estimates')
a = p.parse_args()
if a.stride < 1: p.error('stride must be positive')
d = a.capture
m = json.loads((d/'scene.json').read_text())
w,h = m['width'],m['height']
s = a.stride

def tex(name, channels):
    return np.memmap(d/(name+'.f32'),dtype='<f4',mode='r',shape=(h,w,channels))[::s,::s]

depth = np.asarray(tex('depth',1)[...,0])
normal = tex('normal',3)
material = tex('material',3)
color = tex('color',4)
ys,xs = np.mgrid[0:h:s,0:w:s]
vx,vy,vw,vh = m['frameViewport']
inside = (xs>=vx)&(xs<vx+vw)&(ys>=vy)&(ys<vy+vh)
geometry = inside & (depth > (0 if m['depthZeroToOne'] else .5))
lights = np.fromfile(d/'lights.f32',dtype='<f4').reshape(-1,8)
def light_lists(capture, meta):
    """The light masks (LightClusters) as rows of a count, then the lights in index order, as the shaders walk them."""
    words = np.fromfile(capture/'clusters.u32',dtype='<u4').reshape(-1,meta['CLUSTER_TEXELS']*4)
    bits = np.unpackbits(words.view(np.uint8),axis=1,bitorder='little').astype(bool)
    table = np.zeros((len(bits),meta['MAX_LIGHTS_PER_CLUSTER']+1),dtype=np.int32)
    table[:,0] = bits.sum(axis=1)
    table[:,1:] = np.argsort(~bits,axis=1,kind='stable')[:,:meta['MAX_LIGHTS_PER_CLUSTER']]
    return table

clusters = light_lists(d,m)
nx,ny,nz = [m[k] for k in ('CLUSTERS_X','CLUSTERS_Y','CLUSTERS_Z')]
cx = np.clip(((xs+.5-vx)/vw*nx).astype(int),0,nx-1)
cy = np.clip(((ys+.5-vy)/vh*ny).astype(int),0,ny-1)
view_z = np.zeros_like(depth,dtype=np.float64)
view_z[geometry] = (100/depth[geometry] if m['depthZeroToOne'] else 50/(depth[geometry]-.5))
cz = np.zeros_like(cx)
cz[geometry] = np.clip(np.floor(np.log(np.maximum(view_z[geometry],m['NEAR'])/m['NEAR'])/np.log(m['FAR']/m['NEAR'])*nz),0,nz-1).astype(int)
ci = (cy*nx+cx)*nz+cz
counts = clusters[ci,0].astype(int)
counts[~geometry] = 0
if m.get('config', {}).get('pointLights') == 'false': counts[:] = 0
reference_table = None
missing_pairs = 0
if a.reference:
    rm=json.loads((a.reference/'scene.json').read_text())
    assert rm['frameViewProj']==m['frameViewProj'] and rm['width']==w and rm['height']==h
    assert (a.reference/'lights.f32').read_bytes()==(d/'lights.f32').read_bytes()
    reference_table=light_lists(a.reference,rm)
    rx,ry,rz=(rm[k] for k in ('CLUSTERS_X','CLUSTERS_Y','CLUSTERS_Z'))
    rcx=np.clip(((xs+.5-vx)/vw*rx).astype(int),0,rx-1)
    rcy=np.clip(((ys+.5-vy)/vh*ry).astype(int),0,ry-1)
    rcz=np.zeros_like(cx)
    rcz[geometry]=np.clip(np.floor(np.log(np.maximum(view_z[geometry],rm['NEAR'])/rm['NEAR'])/np.log(rm['FAR']/rm['NEAR'])*rz),0,rz-1).astype(int)
    reference_ci=(rcy*rx+rcx)*rz+rcz
inv = np.asarray(m['frameInvViewProj']).reshape(4,4).T
# Chunk processing avoids a pixel x light x xyz allocation.
flat = np.flatnonzero(geometry)
per_light = np.zeros((len(lights),3),dtype=np.int64)
radius_heat = np.zeros(depth.size,dtype=np.uint16)
facing_heat = np.zeros(depth.size,dtype=np.uint16)
for offset in range(0,len(flat),65536):
    ids = flat[offset:offset+65536]
    px,py = xs.ravel()[ids]+.5, ys.ravel()[ids]+.5
    z = depth.ravel()[ids]
    clip = np.stack(((px-vx)/vw*2-1,(py-vy)/vh*2-1,z if m['depthZeroToOne'] else z*2-1,np.ones(len(ids))),axis=1)
    hp = clip@inv.T
    pos = hp[:,:3]/hp[:,3:]
    cover = np.maximum(np.asarray(color[...,3]).ravel()[ids],1/255)
    n = np.asarray(normal).reshape(-1,3)[ids].astype(np.float64)
    n /= np.where(cover<.996,cover,1)[:,None]
    n = n*2-1
    n /= np.maximum(np.linalg.norm(n,axis=1,keepdims=True),1e-12)
    transmission = np.asarray(material[...,1]).ravel()[ids] / np.where(cover<.996,cover,1)
    table = clusters[ci.ravel()[ids]].copy()
    table[:,0] = counts.ravel()[ids]
    for k in range(int(table[:,0].max(initial=0))):
        take = np.flatnonzero(table[:,0]>k)
        li = table[take,k+1].astype(int)
        delta = lights[li,:3]-pos[take]
        hit = np.einsum('ij,ij->i',delta,delta)<lights[li,3]**2
        if reference_table is not None and hit.any():
            candidate=reference_table[reference_ci.ravel()[ids[take[hit]]]]
            included=((candidate[:,1:]==li[hit,None]) & (np.arange(candidate.shape[1]-1)[None,:]<candidate[:,0,None])).any(axis=1)
            missing_pairs+=int((~included).sum())
        facing = hit & ((np.einsum('ij,ij->i',delta,n[take])>0)|(transmission[take]>0))
        per_light[:,0] += np.bincount(li,minlength=len(lights))
        per_light[:,1] += np.bincount(li[hit],minlength=len(lights))
        per_light[:,2] += np.bincount(li[facing],minlength=len(lights))
        radius_heat[ids[take[hit]]] += 1
        facing_heat[ids[take[facing]]] += 1

scale = s*s
loops,radius,facing = (per_light.sum(axis=0)*scale).tolist()
# Fog columns are a 2D list over the full ray, not the depth-sliced surface list.
fx = np.arange(m['aoWidth']); fy = np.arange(m['aoHeight'])
fx,fy = np.meshgrid(np.floor(fx*m['halfRatio']).astype(int),np.floor(fy*m['halfRatio']).astype(int))
finside = (fx>=vx)&(fx<vx+vw)&(fy>=vy)&(fy<vy+vh)
fcx=np.clip(((fx+.5-vx)/vw*nx).astype(int),0,nx-1)
fcy=np.clip(((fy+.5-vy)/vh*ny).astype(int),0,ny-1)
fcounts=clusters[nx*ny*nz+fcy*nx+fcx,0]
fog_bound=int(fcounts[finside].sum())

rows=list(csv.DictReader((d/'timings.csv').open()))
# Polling may see a frame twice; never count the same frame/query twice.
frames={int(r['frame']):r for r in rows}
gpu={int(r['gpu_frame']):r for r in rows if int(r['gpu_frame'])>=0}

def stats(values):
    v=np.array(values,dtype=float); v=v[np.isfinite(v)]
    return dict(n=len(v),mean=float(v.mean()),p50=float(np.median(v)),p95=float(np.percentile(v,95))) if len(v) else dict(n=0)

stage_stats={}
for key in rows[0]:
    if not key.startswith('gpu:'): continue
    present=[r[key] for r in gpu.values() if r[key]!='']
    st=stats([float(v) for v in present])
    st.update(present=len(present),invalid=sum(not np.isfinite(float(v)) for v in present))
    name = 'lighting + composite' if key == 'gpu:sky/fog + final blend' and m.get('compositeCombined', True) else key[4:]
    stage_stats[name]=st
frame_stats=stats([float(r['frame_ms']) for r in frames.values()])
matching=[r for r in frames.values() if int(r['camera_hash'])==m['camera_hash']]
report=dict(dimensions=[w,h],stride=s,geometry_pixels=int(geometry.sum())*scale,
    surface_light_iterations=loops,inside_radius=radius,front_or_transmissive=facing,
    radius_rejection_fraction=1-radius/max(loops,1),backface_rejection_fraction=(radius-facing)/max(loops,1),
    mean_cluster_count=float(counts[geometry].mean()) if geometry.any() else 0,
    p95_cluster_count=float(np.percentile(counts[geometry],95)) if geometry.any() else 0,
    fog_column_iteration_upper_bound=fog_bound,fog_max_column_count=int(fcounts.max()),
    fog_columns_at_capacity=int((clusters[nx*ny*nz:,0]>=m['MAX_LIGHTS_PER_CLUSTER']).sum()),
    frame_timing=frame_stats,gpu_stage_timings=stage_stats,unique_camera_hashes=len({r['camera_hash'] for r in frames.values()}),
    timing_frames_matching_dump=len(matching),sampled_frames=len(frames),gpu_samples=len(gpu))
assert 0 <= facing <= radius <= loops
assert int(counts.sum()) * scale == loops
report['reference_missing_in_radius_pairs'] = missing_pairs if a.reference else None
report['logical_light_fetches_before_room_checks'] = 2*loops + 2*facing
report['cpu_stage_timings'] = {k[4:]: stats([float(r[k]) for r in frames.values()]) for k in rows[0] if k.startswith('cpu:')}
(d/'analysis.json').write_text(json.dumps(report,indent=2))
with (d/'lights.csv').open('w') as f:
    writer=csv.writer(f);writer.writerow(['index','x_camera','y_camera','z_camera','radius','cluster_pixel_iterations','inside_radius','front_or_transmissive'])
    for i in np.argsort(-per_light[:,0]):writer.writerow([i,*lights[i,:4],*(per_light[i]*scale)])

def heat(name, values):
    v=np.asarray(values).reshape(depth.shape).astype(float)
    peak=max(float(np.percentile(v[geometry],99)) if geometry.any() else 1,1)
    t=np.clip(v/peak,0,1)
    rgb=np.stack((t,np.maximum(1-np.abs(t*2-1),0),1-t),axis=-1)
    rgb[~geometry]=0
    Image.fromarray((rgb[::-1]*255).astype('uint8')).save(d/(name+'.png'))
heat('cluster-work',counts)
heat('useful-light-work',facing_heat)
heat('rejected-light-work',counts-radius_heat.reshape(depth.shape))
lines=['# Scene workload report','',
    '**OFFLINE CULLING REPLAY: timings below are inherited from the source capture, not measurements of the new renderer. Fog lists are not replayed.**' if m.get('replay') else '',
    f'Reference check: {missing_pairs:,} missing in-radius pixel/light pairs.' if a.reference else '',f'Render target: {w} × {h}; analyzed every {s} pixel(s).',
    f'Visible geometry: {report["geometry_pixels"]:,} pixels.',
    f'Raw sampled frame time: median {frame_stats.get("p50",0):.3f} ms, p95 {frame_stats.get("p95",0):.3f} ms.',
    f'Surface light loop: {loops:,} pixel/light pairs; {radius:,} inside radius; {facing:,} front-facing or transmissive.',
    f'Radius rejects {report["radius_rejection_fraction"]:.1%} of candidates; back-face rejection removes a further {report["backface_rejection_fraction"]:.1%}.',
    f'Fog column-loop upper bound: {fog_bound:,} iterations/frame before ray/cylinder/depth early exits.',
    f'Fog columns at capacity: {report["fog_columns_at_capacity"]}.', '',
    '| GPU pass | valid / present | mean ms | p50 ms | p95 ms |','|---|---:|---:|---:|---:|']
for name,st in stage_stats.items():
    if st['n']:lines.append(f'| {name} | {st["n"]}/{st["present"]} | {st["mean"]:.3f} | {st["p50"]:.3f} | {st["p95"]:.3f} |')
    elif st['present']:lines.append(f'| {name} | 0/{st["present"]} | invalid | invalid | invalid |')
lines += ['', '## Work equation', '', f'{report["geometry_pixels"]:,} visible pixels × {report["mean_cluster_count"]:.2f} mean candidates = {loops:,} light-loop iterations/frame.', f'At 200 FPS that would require {loops*200/1e9:.2f} billion candidate iterations/second at this resolution and view. This is a workload target, not a measured hardware throughput limit.', f'{loops:,} candidates → {radius:,} in radius → {facing:,} facing/transmissive → room and wall checks (not simulated).', '', '## Interpretation limits','',
    'Heatmaps: blue = zero, red = 99th percentile workload (each image independently scaled); these show work, not measured spatial GPU time.',
    'GPU samples are raw query results, not retained overlay values. Missing queries are blank; invalid values are excluded and counted. Skipped passes are omitted; zero-duration queries may represent a no-op or timer resolution.',
    'CPU samples are elapsed time and can include GPU/driver waits. CPU and GPU times overlap and must not be summed.',
    f'Timing window contains {report["unique_camera_hashes"]} camera matrices; {len(matching)}/{len(frames)} sampled frames match the dumped camera. GPU frame IDs refer to earlier frames; camera matching here is for CPU/frame samples only.',
    'Light workload counts stop before room and wall visibility rejection. They are not counts of contributing lights or shader instructions. Fog counts are an upper bound, not measured executed iterations.',
    'Texture readback occurs after timing and may visibly hitch once. This is a rendered-scene snapshot, not a replayable mesh/world dump. Dynamic actors, flicker and weather can change during sampling.',
    'Logical texture fetches do not equal physical memory traffic; cache hit rates, occupancy, register spills and hardware bandwidth require a GPU profiler. Per-pass timings include shared setup and are not additive per-feature attribution.']
(d/'report.md').write_text('\n'.join(lines)+'\n')
print('\n'.join(lines))
