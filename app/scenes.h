// ===========================================================================
// scenes.h -- scene state machine (plan §5.3, §6)
// ===========================================================================
// Rotation runs continuously, all day. Touch layers three behaviours on top
// (plan §6), all of which are visible in the status strip:
//
//   tap        -> advance now, and freeze auto-rotation for 45 s so a glance
//                 doesn't get yanked away mid-read
//   long press -> pin the current scene until pressed again
//   (a > 4 s hold is handled in app.ino: it re-runs touch calibration)
//
// Adding a scene later is still one array entry.
#pragma once

#include <Arduino.h>
#include "touch.h"

struct Scene {
  const char* name;
  uint32_t    dwellMs;
  void (*onEnter)();   // full redraw of the content area
  void (*onTick)();    // partial redraw only (may be null)
  void (*onExit)();    // cleanup, e.g. free sprites (may be null)
  // How long a tap ONTO this scene suspends auto-rotation. 0 means "use
  // SCENE_FREEZE_MS", which is what every scene wanted until the bus scene --
  // where you tap to watch a countdown, not to read a number, so 45 s is not
  // long enough to be worth the tap.
  //
  // Deliberately the LAST member: C++11 aggregate initialisation
  // value-initialises omitted trailing members, so every existing row in the
  // scene table compiles unchanged and means exactly what it did before.
  uint32_t    freezeMs;
};

void sceneManager_begin();   // enter the first scene
void sceneManager_tick();    // call every loop; ticks + advances on dwell
int  sceneManager_index();   // current scene index
int  sceneManager_count();   // total scenes

// Feed a gesture in. TOUCH_NONE is ignored, so this is safe to call every loop.
void sceneManager_handleTouch(TouchEvent ev);

// Status-strip state.
bool sceneManager_isPinned();
bool sceneManager_isFrozen();

// How long a tap suspends auto-rotation (plan §6).
static const uint32_t SCENE_FREEZE_MS = 45000;
