import { useRef } from "react";
import { useFrame, useThree } from "@react-three/fiber";
import * as THREE from "three";

const POINTER_SMOOTHING = 0.014;
const POINTER_EXTENT = 0.38;
const BASE_SCALE = 1.28;
const SCALE_SMOOTHING = 0.009;
const EASE_IN_SECONDS = 2;

const vert = `
void main() {
  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
}
`;

const frag = `
uniform float time;
uniform float animRate;
uniform float patternScale;
uniform vec3 brightTone;
uniform vec3 midTone;
uniform vec3 darkTone;
uniform vec2 viewport;

float FREQ_A = 3.1;
float FREQ_B = 0.95;
float FREQ_C = 19.5;
float TIME_SCALE = 1.0;
float GAMMA = 3.0;

float lateralWave(vec2 p, float t) {
  return cos(FREQ_A * p.x + TIME_SCALE * t);
}

float sweepWave(vec2 p, float t) {
  return cos(FREQ_B * (p.x * cos(t) + 4.8 * p.y * sin(t)) + TIME_SCALE * t);
}

float concentricWave(vec2 p, float t) {
  float cx = 0.28 * p.x - 0.48 + cos(t);
  float cy = 0.28 * p.y - 0.48 + sin(t * 0.5);
  return sin(FREQ_C * sqrt(cx * cx + cy * cy + 1.0) + TIME_SCALE * t);
}

void main() {
  float t = time * animRate + 850.0;

  vec2 uv = gl_FragCoord.xy / viewport - 0.5;
  uv *= patternScale;
  uv += 0.5;

  float wave = (lateralWave(uv, t) + sweepWave(uv, t) + concentricWave(uv, t)) / 3.0;
  float n = (wave + 1.0) * 0.5;

  vec3 col;
  if (n < 0.5) {
    col = mix(darkTone, midTone, n * 2.0);
  } else {
    col = mix(midTone, brightTone, (n - 0.5) * 2.0);
  }

  gl_FragColor = vec4(pow(col, vec3(GAMMA)), 1.0);
}
`;

export function BackdropMesh() {
  const matRef = useRef<THREE.ShaderMaterial>(null!);
  const meshRef = useRef<THREE.Mesh>(null!);
  const { size } = useThree();

  const smoothed = useRef(new THREE.Vector2());
  const goal = useRef(new THREE.Vector2());

  useFrame(({ clock, pointer }) => {
    goal.current.set(pointer.x * POINTER_EXTENT, pointer.y * POINTER_EXTENT);
    smoothed.current.lerp(goal.current, POINTER_SMOOTHING);

    if (meshRef.current) {
      meshRef.current.position.x = smoothed.current.x * 2.8;
      meshRef.current.position.y = smoothed.current.y * 2.8;

      const progress = Math.min(clock.getElapsedTime() / EASE_IN_SECONDS, 1.0);
      const eased = progress * progress * progress;
      const d = Math.sqrt(smoothed.current.x ** 2 + smoothed.current.y ** 2);
      const boost = d * 0.75;
      const prev = meshRef.current.scale.x;
      const next = prev + (BASE_SCALE + boost - prev) * SCALE_SMOOTHING * eased;
      meshRef.current.scale.set(next, next * 0.74, next);
    }

    if (matRef.current) {
      matRef.current.uniforms.time.value = clock.getElapsedTime();
      matRef.current.uniforms.viewport.value.set(size.width, size.height);
    }
  });

  return (
    <mesh ref={meshRef} position={[0, 0, -16]} scale={0}>
      <icosahedronGeometry args={[5, 2]} />
      <shaderMaterial
        ref={matRef}
        vertexShader={vert}
        fragmentShader={frag}
        uniforms={{
          time: { value: 0 },
          animRate: { value: 0.1 },
          patternScale: { value: 0.98 },
          brightTone: { value: new THREE.Color(1.0, 1.0, 1.0) },
          midTone: { value: new THREE.Color(0.9, 0.9, 0.9) },
          darkTone: { value: new THREE.Color(0.76, 0.76, 0.76) },
          viewport: { value: new THREE.Vector2(size.width, size.height) },
        }}
      />
    </mesh>
  );
}
