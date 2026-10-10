import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

const host = document.querySelector('#canvas-host');
const loading = document.querySelector('#loading');
const loadingText = document.querySelector('#loading-text');
const seek = document.querySelector('#seek');
const timeLabel = document.querySelector('#time');
const playButton = document.querySelector('#play');
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(32, 1, 0.01, 100);
let renderer;
try {
  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
} catch (error) {
  loadingText.textContent = 'WebGL недоступен. Скачайте GLB для просмотра в Blender.';
  throw error;
}
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.05;
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
host.appendChild(renderer.domElement);
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.07;
controls.minPolarAngle = 0.25;
controls.maxPolarAngle = Math.PI / 2 + 0.12;

scene.add(new THREE.HemisphereLight(0xcfd9ff, 0x6b6479, 2.2));
const key = new THREE.DirectionalLight(0xffeddf, 3);
key.position.set(-1.5, 2.8, 2.5);
key.castShadow = true;
key.shadow.mapSize.set(2048, 2048);
key.shadow.bias = -0.0005;
key.shadow.normalBias = 0.005;
key.shadow.camera.left = -1; key.shadow.camera.right = 1;
key.shadow.camera.top = 1.5; key.shadow.camera.bottom = -0.5;
key.shadow.camera.near = 0.1; key.shadow.camera.far = 10;
scene.add(key);
const fill = new THREE.DirectionalLight(0xc8caff, 1.3);
fill.position.set(1.5, 1, 0.5); scene.add(fill);
const rim = new THREE.DirectionalLight(0xe9daff, 2);
rim.position.set(0.5, 2.5, -1.5); scene.add(rim);

const ground = new THREE.Mesh(new THREE.CircleGeometry(1.35, 96),
  new THREE.MeshStandardMaterial({ color: 0x343946, roughness: 0.95 }));
ground.rotation.x = -Math.PI / 2;
ground.position.y = -0.003;
ground.receiveShadow = true;
scene.add(ground);
const ring = new THREE.Mesh(new THREE.RingGeometry(0.64, 0.643, 96),
  new THREE.MeshBasicMaterial({ color: 0x665c7c, side: THREE.DoubleSide, transparent: true, opacity: 0.5 }));
ring.rotation.x = -Math.PI / 2; ring.position.y = -0.001; scene.add(ring);

let mixer, model, helper, activeAction, height = 1, currentClip = 'Idle', paused = false;
let fadingActions = [];
const actions = new Map();
const clock = new THREE.Clock();
let seeking = false;

function resize() {
  const { width, height } = host.getBoundingClientRect();
  renderer.setSize(width, height);
  camera.aspect = width / height; camera.updateProjectionMatrix();
}
new ResizeObserver(resize).observe(host);
resize();

function resetCamera() {
  controls.target.set(0, height * 0.52, 0);
  camera.position.set(height * 0.33, height * 0.60, height * 2.35);
  controls.minDistance = height * 0.8; controls.maxDistance = height * 4.8;
  camera.near = height * 0.01; camera.far = height * 100;
  camera.updateProjectionMatrix(); controls.update();
}
document.querySelector('#reset-camera').addEventListener('click', resetCamera);

function setClip(name) {
  const next = actions.get(name);
  if (!next) return;
  paused = false;
  playButton.textContent = 'Ⅱ'; playButton.setAttribute('aria-label', 'Пауза');
  if (activeAction && activeAction !== next) {
    activeAction.fadeOut(0.22);
    fadingActions.push({ action: activeAction, remaining: 0.25 });
  }
  if (activeAction !== next) next.reset().fadeIn(0.22).play();
  else next.reset().setEffectiveWeight(1).play();
  activeAction = next; activeAction.paused = false;
  currentClip = name;
  seek.max = next.getClip().duration;
  document.querySelector('#clip-tag').textContent = name.replace('_InPlace', '').toUpperCase();
  for (const button of document.querySelectorAll('[data-clip]')) {
    button.classList.toggle('selected', button.dataset.clip === name);
    button.setAttribute('aria-pressed', String(button.dataset.clip === name));
  }
}
for (const button of document.querySelectorAll('[data-clip]'))
  button.addEventListener('click', () => setClip(button.dataset.clip));

playButton.addEventListener('click', () => {
  if (!activeAction) { setClip(currentClip); return; }
  paused = !paused;
  activeAction.paused = paused;
  if (!paused && activeAction.time >= activeAction.getClip().duration) activeAction.reset().play();
  playButton.textContent = paused ? '▶' : 'Ⅱ';
  playButton.setAttribute('aria-label', paused ? 'Воспроизвести' : 'Пауза');
});
seek.addEventListener('pointerdown', () => { seeking = true; });
window.addEventListener('pointerup', () => { seeking = false; });
seek.addEventListener('input', () => {
  if (!activeAction) return;
  activeAction.enabled = true;
  activeAction.time = Number(seek.value);
  mixer.update(0);
});
document.querySelector('#speed').addEventListener('change', (event) => {
  if (mixer) mixer.timeScale = Number(event.target.value);
});
document.querySelector('#skeleton').addEventListener('change', (event) => {
  if (helper) helper.visible = event.target.checked;
});
document.querySelector('#rest').addEventListener('click', () => {
  if (!mixer) return;
  mixer.stopAllAction(); activeAction = null; fadingActions = [];
  paused = true; playButton.textContent = '▶';
  document.querySelector('#clip-tag').textContent = 'REST POSE';
  for (const button of document.querySelectorAll('[data-clip]')) {
    button.classList.remove('selected'); button.setAttribute('aria-pressed', 'false');
  }
  seek.value = 0; timeLabel.textContent = 'исходная поза';
});

fetch('/report.json').then((r) => { if (!r.ok) throw new Error('report unavailable'); return r.json(); })
  .then((report) => {
    document.querySelector('#bone-count').textContent = report.bones.length;
    document.querySelector('#triangle-count').textContent = `${Math.round(report.runtime.triangles / 1000)}k`;
  }).catch(() => {});

new GLTFLoader().load('/model.glb', (gltf) => {
  model = gltf.scene;
  model.traverse((object) => {
    if (object.isMesh) { object.castShadow = true; object.receiveShadow = true; }
  });
  scene.add(model);
  const bounds = new THREE.Box3().setFromObject(model);
  height = bounds.max.y - bounds.min.y;
  model.position.y -= bounds.min.y;
  ground.scale.setScalar(height); ring.scale.setScalar(height);
  key.shadow.camera.left = -height; key.shadow.camera.right = height;
  key.shadow.camera.top = 1.5 * height; key.shadow.camera.bottom = -0.5 * height;
  key.shadow.camera.updateProjectionMatrix();
  resetCamera();
  helper = new THREE.SkeletonHelper(model);
  helper.material.depthTest = false;
  helper.material.transparent = true;
  helper.material.opacity = 0.85;
  helper.renderOrder = 100;
  helper.visible = document.querySelector('#skeleton').checked;
  scene.add(helper);
  mixer = new THREE.AnimationMixer(model);
  mixer.addEventListener('finished', (event) => {
    if (event.action === activeAction) {
      paused = true;
      playButton.textContent = '▶';
      playButton.setAttribute('aria-label', 'Воспроизвести');
    }
  });
  for (const clip of gltf.animations) {
    const action = mixer.clipAction(clip);
    if (clip.name === 'Wave') { action.setLoop(THREE.LoopOnce, 1); action.clampWhenFinished = true; }
    else action.setLoop(THREE.LoopRepeat, Infinity);
    actions.set(clip.name, action);
  }
  if (actions.has('Idle')) setClip('Idle');
  else loadingText.textContent = 'В модели не найден ожидаемый клип Idle.';
  loading.classList.add('hidden');
  // Exposed only for automated viewer smoke tests; no source files or credentials.
  window.rigViewer = { scene, model, mixer, actions, get helper() { return helper; }, setClip };
}, (progress) => {
  if (progress.total) loadingText.textContent = `Загружаем модель… ${Math.round(progress.loaded / progress.total * 100)}%`;
}, (error) => {
  console.error(error);
  loadingText.textContent = 'Не удалось загрузить результат. Проверьте завершение GitHub Actions.';
});

renderer.setAnimationLoop(() => {
  const delta = Math.min(clock.getDelta(), 0.05);
  if (mixer) {
    if (!seeking) mixer.update(delta);
    fadingActions = fadingActions.filter((entry) => {
      entry.remaining -= delta;
      if (entry.remaining <= 0) {
        if (entry.action !== activeAction) entry.action.stop();
        return false;
      }
      return true;
    });
    if (activeAction) {
      const duration = activeAction.getClip().duration;
      if (!seeking) seek.value = activeAction.time;
      timeLabel.textContent = `${activeAction.time.toFixed(1)} / ${duration.toFixed(1)}`;
    }
  }
  controls.update();
  renderer.render(scene, camera);
});
