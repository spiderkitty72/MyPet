// Rule tests for the web port, mirroring core/src/test/.../PetGameTest.kt.
// Run with:  node --test web/test/
import { test } from "node:test";
import assert from "node:assert/strict";
import { EDITIONS } from "../js/data.js";
import { PetGame, R, C, CallKind, Stage, edition, formOf, formsOf, stageOf, isAlive, ageDays, minuteOfDay, isBedtime, normalizeSave } from "../js/game.js";
import { MiniGame, ROUNDS } from "../js/minigame.js";

const day = C.MINUTES_PER_DAY;

function newGame(ed = "pebble", seed = 42) {
  const g = new PetGame(null);
  g.newEgg(ed, "Testy", 0, 10 * 60, seed);
  return g;
}

function perfectCare(g) {
  const p = g.pet;
  if (!isAlive(p) || stageOf(p) === Stage.EGG) return;
  if (p.asleep) {
    if (p.lightsOn) g.toggleLights();
    return;
  }
  if (!p.lightsOn) g.toggleLights();
  if (p.call === CallKind.FAKE) g.scold();
  if (p.sick) g.giveMedicine();
  if (p.poops > 0) g.clean();
  while (p.hunger < 4 && g.feedMeal() === R.ATE_MEAL);
  while (p.happy < 4) {
    if (g.canPlay() !== R.OK) break;
    g.nextSeed();
    g.finishGame(true);
  }
}

function run(g, minutes, care) {
  for (let i = 0; i < minutes; i++) {
    g.skipMinutes(1);
    if (care) perfectCare(g);
  }
}

test("every sprite fits the screen", () => {
  assert.equal(EDITIONS.length, 4);
  for (const e of EDITIONS) {
    const forms = formsOf(e);
    assert.equal(new Set(forms.map((f) => f.id)).size, 9);
    for (const f of forms) {
      const w = Math.max(...f.sprite.map((r) => r.length));
      assert.ok(w >= 6 && w <= 16, `${f.id} width ${w}`);
      assert.ok(f.sprite.length >= 5 && f.sprite.length <= 16, `${f.id} height`);
    }
  }
});

test("egg hatches after five minutes", () => {
  const g = newGame();
  g.skipMinutes(4);
  assert.equal(stageOf(g.pet), Stage.EGG);
  g.skipMinutes(1);
  assert.equal(g.pet.formId, "pebble_baby");
  assert.ok(g.save.discovered.includes("pebble_baby"));
});

test("ignored hunger becomes one care mistake", () => {
  const g = newGame();
  g.skipMinutes(5);
  const p = g.pet;
  p.happy = 4;
  p.hunger = 0;
  g.skipMinutes(1);
  assert.equal(p.call, CallKind.HUNGER);
  g.skipMinutes(C.CALL_WINDOW_MINUTES);
  assert.equal(p.careMistakes, 1);
  assert.equal(p.call, null);
  g.skipMinutes(30);
  assert.equal(p.careMistakes, 1);
});

test("feeding answers the call and full pets refuse", () => {
  const g = newGame();
  g.skipMinutes(5);
  const p = g.pet;
  p.hunger = 0;
  g.skipMinutes(1);
  assert.equal(g.feedMeal(), R.ATE_MEAL);
  assert.equal(p.call, null);
  for (let i = 0; i < 3; i++) g.feedMeal();
  assert.equal(p.hunger, 4);
  assert.equal(g.feedMeal(), R.FULL);
});

test("too many snacks cause a tummy ache", () => {
  const g = newGame();
  g.skipMinutes(5);
  for (let i = 0; i < edition("pebble").rules.snackLimit; i++) g.feedSnack();
  assert.ok(g.pet.sick);
  let r;
  do r = g.giveMedicine();
  while (r === R.DOSE_GIVEN);
  assert.equal(r, R.CURED);
});

test("scolding only helps during a fake call", () => {
  const g = newGame();
  g.skipMinutes(5);
  const p = g.pet;
  p.happy = 3;
  assert.equal(g.scold(), R.SCOLD_BAD);
  assert.equal(p.happy, 2);
  p.call = CallKind.FAKE;
  p.callStartMinute = p.gameMinutes;
  assert.equal(g.feedMeal(), R.REFUSED);
  assert.equal(g.scold(), R.SCOLD_GOOD);
  assert.equal(p.discipline, 1);
});

test("pet sleeps at bedtime and wants the light off", () => {
  const g = newGame();
  run(g, 5 + C.BABY_MINUTES + 1, true);
  const p = g.pet;
  assert.equal(stageOf(p), Stage.CHILD);
  run(g, 21 * 60 - minuteOfDay(p) - 1, true);
  assert.equal(p.asleep, false);
  g.skipMinutes(1);
  assert.equal(p.asleep, true);
  g.skipMinutes(1);
  assert.equal(p.call, CallKind.LIGHTS);
  g.toggleLights();
  assert.equal(p.call, null);
  assert.equal(g.feedMeal(), R.ASLEEP);
  g.skipMinutes(11 * 60);
  assert.equal(p.asleep, false);
  assert.equal(p.lightsOn, true);
});

test("good care grows the best adult, perfect care the secret form", () => {
  const g = newGame();
  run(g, 10, true);
  g.pet.discipline = 4;
  run(g, 5 * day, true);
  assert.equal(g.pet.formId, "pebble_adult_best");
  assert.equal(g.pet.careMistakes, 0);
  run(g, 2 * day, true);
  assert.equal(stageOf(g.pet), Stage.SECRET);
});

test("total neglect eventually ends the game", () => {
  const g = newGame();
  run(g, 4 * day, false);
  assert.ok(g.pet.ending);
  assert.equal(g.save.history.length, 1);
});

test("a well-kept pet lives out its lifespan", () => {
  const g = newGame();
  run(g, 25 * day, true);
  const p = g.pet;
  assert.equal(p.ending, "OLD_AGE");
  const expected = Math.floor(formOf(p).lifespanDays);
  assert.ok(ageDays(p) >= expected - 1 && ageDays(p) <= expected, `age ${ageDays(p)} form ${p.formId}`);
});

test("Nova grows up twice as fast", () => {
  const g = newGame("nova");
  run(g, 10, true);
  g.pet.discipline = 4;
  run(g, Math.floor(2.6 * day), true);
  assert.equal(stageOf(g.pet), Stage.ADULT);
});

test("time passing while closed is caught up from timestamps, honouring speed and pause", () => {
  const g = newGame();
  const minute = C.MILLIS_PER_MINUTE;
  assert.equal(g.advanceTo(10 * minute), 10);
  g.setSpeed(4, 10 * minute);
  assert.equal(g.advanceTo(20 * minute), 40);
  g.setPaused(true, 20 * minute);
  assert.equal(g.advanceTo(80 * minute), 0);
  g.setPaused(false, 80 * minute);
  assert.equal(g.advanceTo(81 * minute), 4);
});

test("a long absence drains stats as if the app had stayed open", () => {
  const closed = newGame("pebble", 9);
  const open = newGame("pebble", 9);
  const minute = C.MILLIS_PER_MINUTE;
  // One app checks every minute; the other is "closed" for 3 hours, then reopened.
  for (let t = 1; t <= 180; t++) open.advanceTo(t * minute);
  const restored = new PetGame(JSON.parse(JSON.stringify(closed.save)));
  restored.advanceTo(180 * minute);
  assert.deepEqual(restored.save.pet, open.save.pet);
  assert.ok(restored.pet.hunger < 4);
});

test("save survives JSON and bad saves start fresh", () => {
  const g = newGame("tide");
  run(g, 200, true);
  const copy = new PetGame(JSON.parse(JSON.stringify(g.save)));
  assert.deepEqual(copy.save, g.save);
  assert.equal(normalizeSave("nope").pet, null);
  assert.equal(normalizeSave(null).pet, null);
});

test("mini-games need three wins of five", () => {
  const w = new MiniGame("WHICH_WAY", 7);
  for (let i = 0; i < ROUNDS; i++) w.guessWay(true);
  assert.ok(w.finished);
  assert.equal(w.won, w.wins >= 3);
  const h = new MiniGame("HI_LO", 3);
  for (let i = 0; i < ROUNDS; i++) {
    const before = h.shown;
    assert.equal(h.guessHigher(true), h.revealed > before);
  }
  const c = new MiniGame("CATCH", 1);
  [true, true, true, false, false].forEach((x) => c.submitCatch(x));
  assert.ok(c.won);
});

test("bedtime wraps past midnight", () => {
  const rules = edition("nova").rules;
  assert.ok(isBedtime(23 * 60 + 30, rules));
  assert.ok(isBedtime(3 * 60, rules));
  assert.ok(!isBedtime(12 * 60, rules));
});

test("predicts the next call without changing the game", () => {
  const g = newGame();
  g.skipMinutes(5);
  const p = g.pet;
  Object.assign(p, { hunger: 1, happy: 4, hungerTimer: 30, happyTimer: 500 });
  const before = JSON.stringify(g.save);
  const minutes = g.minutesUntilNextCall();
  assert.equal(JSON.stringify(g.save), before);
  assert.ok(minutes != null && minutes <= 30);
  g.skipMinutes(minutes);
  assert.ok(p.call);
});
