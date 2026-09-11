# ArcAr Alpha 0.1 — Gameplay Core Prototype

Minimal playable core:

**AUTO + BOLA + FÍSICAS + INPUT + MECÁNICAS + CÁMARA MÍNIMA + SUELO PLANO**

## What remains

- Native ArcArSim + Bullet Physics (Car, Ball, Arena, fixed timestep)
- Plane-only arena (no stadium meshes; floor/wall/ceiling planes)
- Simple visual: car = oriented box (hitbox-aligned), ball = sphere, ground = flat plane
- Chase camera + Ball Cam (toggle with **C**)
- Full vehicle controls via InputMapper (throttle, steer, pitch, yaw, roll, jump, boost, handbrake)
- Reset with **R**

## What was removed

- All HUD / menus / settings / console UI
- SettingsActivity, CarCatalog, AssetStore, GlbModel
- GLB models, textures, VFX, particles, shadows, boost trails, goal effects
- Camera shake, dynamic FOV spectacle, impact VFX

## Architecture

```
Android Input → NativeBridge → GameEngine
                                  ├── Arena (planes)
                                  ├── Car (BODY_C hitbox)
                                  └── Ball
                              → RenderSnapshot (minimal)
                              → GameRenderer (box + sphere + plane)
```

## Controls (defaults)

- W / Accel trigger: throttle
- S / Brake trigger: reverse
- Left stick: steer / pitch / yaw
- A / Space: jump
- B / Shift: boost
- X / Ctrl: powerslide
- L1/Q / R1/E: air roll
- C: toggle ball cam
- R: reset kickoff

Visual intentionally ugly. Physics is the product.
