// Animated lava surface only: no sky reflection, ray march, extra geometry or actor physics.
// World-space positions keep crust attached to the flow instead of the camera.
#ifndef LAVA_GLSL
#define LAVA_GLSL
// Same representative linear hue as LavaMaterials.EMISSION_*.
const vec3 LAVA_EMISSION = vec3(0.65,0.12,0.006);
vec2 lavaHash(vec2 p) {
  return fract(sin(vec2(dot(p, vec2(127.1,311.7)),dot(p,vec2(269.5,183.3)))) * 43758.5453);
}
float lavaNoise(vec2 p) {
  vec2 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f);
  return mix(mix(lavaHash(i).x,lavaHash(i+vec2(1,0)).x,f.x),
    mix(lavaHash(i+vec2(0,1)).x,lavaHash(i+vec2(1,1)).x,f.x),f.y);
}

// Returns linear radiance. Types vary crust coverage/flow, not the requested red-orange palette.
vec3 lavaSurface(vec3 world, vec3 normal, vec3 viewDir, float seconds, float variant,
    vec3 sunDirection, float sunAmount) {
  float clock = seconds * 0.035;
  vec2 drift = vec2(0.075,0.035) * clock;
  vec2 p = world.xz / 220.0 - drift;
  if (variant == 3.0) p = vec2(p.x * 0.65, p.y * 1.45); // slow rope-like third variant
  // Broad molten spans and uneven channels, not a repeated field of rounded cells.
  vec2 warp = vec2(lavaNoise(p * 0.37), lavaNoise(p * 0.37 + 17.3)) - 0.5;
  p += warp * 0.7;
  vec2 slow = p + vec2(lavaNoise(p*0.47+clock*0.025),lavaNoise(p*0.47-clock*0.019)) * 0.12;
  float heat = lavaNoise(slow*0.75) * 0.72 + lavaNoise(slow*vec2(2.1,0.6)+clock*0.025) * 0.28;
  heat = smoothstep(0.20,0.82,heat);

  // Uneven clusters of angular plates/shards. Spatially varying coverage leaves large open molten spans.
  // Different size, aspect, rotation and clipped corners prevent the former evenly spaced rounded spots.
  vec2 cell=floor(p), local=fract(p);
  float rock=8.0, bubble=0.0;
  for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) {
    vec2 offset=vec2(x,y), seed=lavaHash(cell+offset);
    vec2 second=lavaHash(cell+offset+vec2(39.2,7.1));
    float coverage=mix(0.16,0.9,smoothstep(0.26,0.74,lavaNoise((cell+offset)*0.29)));
    vec2 center=offset+0.5+(seed-0.5)*0.95;
    center += 0.018*sin(vec2(1.0,1.17)*clock+seed*6.2831853);
    if (second.x < coverage) {
      vec2 q=local-center;
      float angle=seed.x*6.2831853;
      q=mat2(cos(angle),-sin(angle),sin(angle),cos(angle))*q;
      float radius=mix(0.13,0.56,second.y*second.y)+(variant==2.0?0.06:0.0);
      q /= vec2(radius,radius*mix(0.35,1.25,seed.y));
      // Intersect skewed half-planes: sharp fractured silhouettes with unequal clipped corners.
      float shape=max(max(q.x,-q.x*mix(0.7,1.3,seed.y)),max(q.y,-q.y*mix(0.75,1.25,second.x)));
      shape=max(shape,dot(q,normalize(vec2(0.6,1.0)))*mix(0.8,1.2,seed.x));
      shape=max(shape,dot(q,normalize(vec2(-1.0,-0.4)))*mix(0.75,1.1,second.y));
      shape += (lavaNoise(q*3.0+seed*15.0)-0.5)*0.12;
      rock=min(rock,shape);
    }
    // Sparse slow domes remain subtle; no repeated circular rim or water-like foam.
    if (second.y > 0.82) {
      float phase=fract(clock*0.10+seed.y);
      float life=smoothstep(0.05,0.35,phase)*(1.0-smoothstep(0.65,0.95,phase));
      float dist=length(local-offset-seed);
      bubble=max(bubble,(1.0-smoothstep(0.02,0.24,dist))*life);
    }
  }
  float aa=max(fwidth(rock),0.012);
  float crust=1.0-smoothstep(1.0-aa,1.0+aa,rock);
  float edge=1.0-smoothstep(1.0,1.08,rock);
  float glow=clamp(heat+edge*0.06+bubble*0.07,0.0,1.0);
  vec3 molten=mix(vec3(0.30,0.012,0.0015),LAVA_EMISSION * 1.45,glow);
  molten=mix(molten,vec3(1.0,0.49,0.035),smoothstep(0.72,1.0,glow)*0.72);
  float facets=lavaNoise(p*8.0);
  vec3 stone=mix(vec3(0.013,0.009,0.007),vec3(0.055,0.033,0.021),facets);
  stone += vec3(0.10,0.012,0.001)*smoothstep(0.88,1.0,rock);

  // Tiny broad direct highlight; there is deliberately no sky/cubemap reflection.
  vec3 ripple=vec3(sin(p.x*2.2+clock)*0.07,0.0,cos(p.y*1.8-clock*0.7)*0.07);
  vec3 n=normalize(normal+ripple);
  vec3 h=normalize(sunDirection+viewDir);
  float highlight=pow(max(dot(n,h),0.0),12.0)*0.025*sunAmount;
  return mix(molten,stone,crust)+vec3(1.0,0.55,0.18)*highlight*(1.0-crust);
}
#endif
