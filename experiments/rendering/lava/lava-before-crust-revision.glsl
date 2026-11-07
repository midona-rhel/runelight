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
  vec2 p = world.xz / 160.0 - drift;
  if (variant == 3.0) p = vec2(p.x * 0.65, p.y * 1.45); // slow rope-like third variant
  vec2 slow = p + vec2(lavaNoise(p*0.47+clock*0.025),lavaNoise(p*0.47-clock*0.019)) * 0.18;
  float heat = lavaNoise(slow*1.8) * 0.65 + lavaNoise(slow*4.0+clock*0.025) * 0.35;
  heat = smoothstep(0.18,0.84,heat);

  // Rounded, irregular crust islands: nine nearby cells, no geometry-sized loops.
  vec2 cell=floor(p), local=fract(p);
  float rock=8.0, bubble=0.0, crest=0.0;
  for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) {
    vec2 offset=vec2(x,y), seed=lavaHash(cell+offset);
    vec2 center=offset+0.5+(seed-0.5)*0.6;
    center += 0.035*sin(vec2(1.0,1.17)*clock+seed*6.2831853);
    vec2 q=local-center;
    float angle=seed.x*6.2831853;
    q=mat2(cos(angle),-sin(angle),sin(angle),cos(angle))*q;
    float radius=mix(0.20,0.39,seed.y)+(variant==2.0?0.055:0.0);
    float shape=length(q*vec2(1.0,1.25+seed.x*0.5))/radius;
    shape += 0.12*sin(atan(q.y,q.x)*5.0+seed.x*12.0);
    rock=min(rock,shape);
    // A broad, slow dome opens and subsides rather than spraying water-like foam.
    float phase=fract(clock*0.10+seed.y);
    float life=smoothstep(0.05,0.35,phase)*(1.0-smoothstep(0.65,0.95,phase));
    float dist=length(local-offset-seed);
    float dome=(1.0-smoothstep(0.02,0.22,dist))*life;
    bubble=max(bubble,dome);
    crest=max(crest,exp(-pow((dist-0.14)/0.055,2.0))*life);
  }
  float aa=max(fwidth(rock),0.035);
  float crust=1.0-smoothstep(0.92-aa,1.02+aa,rock);
  float edge=exp(-pow((rock-1.06)/0.16,2.0));
  float glow=clamp(heat+edge*0.25+bubble*0.12,0.0,1.0);
  vec3 molten=mix(vec3(0.30,0.012,0.0015),LAVA_EMISSION * 1.45,glow);
  molten=mix(molten,vec3(1.0,0.49,0.035),smoothstep(0.72,1.0,glow)*0.72);
  molten *= 1.0-0.14*crest;
  float facets=lavaNoise(p*8.0);
  vec3 stone=mix(vec3(0.013,0.009,0.007),vec3(0.055,0.033,0.021),facets);
  stone += vec3(0.16,0.018,0.002)*smoothstep(0.60,1.0,rock);

  // Tiny broad direct highlight; there is deliberately no sky/cubemap reflection.
  vec3 ripple=vec3(sin(p.x*2.2+clock)*0.07,0.0,cos(p.y*1.8-clock*0.7)*0.07);
  vec3 n=normalize(normal+ripple);
  vec3 h=normalize(sunDirection+viewDir);
  float highlight=pow(max(dot(n,h),0.0),12.0)*0.025*sunAmount;
  return mix(molten,stone,crust)+vec3(1.0,0.55,0.18)*highlight*(1.0-crust);
}
#endif
