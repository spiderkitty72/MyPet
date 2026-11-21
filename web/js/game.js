// The rules engine: a JavaScript port of core/src/main/kotlin/com/mypet/core/PetGame.kt.
// It is a pure function of the save data and the clock. Stats are never decayed on a timer;
// instead, every time the page opens (or wakes up) the simulation is run forward minute by
// minute from the last saved timestamp, so the pet ages correctly while the app is closed.

import { EDITIONS } from "./data.js";

export const Stage = { EGG: "EGG", BABY: "BABY", CHILD: "CHILD", TEEN: "TEEN", ADULT: "ADULT", SECRET: "SECRET" };
export const STAGE_LABEL = { EGG: "Egg", BABY: "Baby", CHILD: "Child", TEEN: "Teen", ADULT: "Adult", SECRET: "Secret" };

export const CallKind = { HUNGER: "HUNGER", HAPPY: "HAPPY", SICK: "SICK", LIGHTS: "LIGHTS", FAKE: "FAKE" };

export const ENDINGS = {
  OLD_AGE: { classic: "Lived a long, full life.", gentle: "Grew up and headed home." },
  STARVED: { classic: "Went hungry for too long.", gentle: "Wandered off to find food." },
  ILLNESS: { classic: "Was sick for too long.", gentle: "Went home to get better." },
};

export const GAMES = {
  WHICH_WAY: { title: "Which Way?", help: "Guess which way your pet will turn. Win 3 of 5." },
  HI_LO: { title: "Hi or Lo", help: "Will the next number be higher or lower? Win 3 of 5." },
  CATCH: { title: "Star Catch", help: "Tap STOP while the star is over the basket. Win 3 of 5." },
};

export const R = {
  OK: "OK", ATE_MEAL: "ATE_MEAL", ATE_SNACK: "ATE_SNACK", FULL: "FULL", REFUSED: "REFUSED",
  CLEANED: "CLEANED", NOTHING_TO_CLEAN: "NOTHING_TO_CLEAN", DOSE_GIVEN: "DOSE_GIVEN", CURED: "CURED",
  NOT_SICK: "NOT_SICK", LIGHTS_ON: "LIGHTS_ON", LIGHTS_OFF: "LIGHTS_OFF", SCOLD_GOOD: "SCOLD_GOOD",
  SCOLD_BAD: "SCOLD_BAD", GAME_WON: "GAME_WON", GAME_LOST: "GAME_LOST", ASLEEP: "ASLEEP", DARK: "DARK",
  STILL_EGG: "STILL_EGG", NO_PET: "NO_PET",
};

export const C = {
  MAX_HEARTS: 4,
  MAX_POOPS: 4,
  MAX_NAME: 10,
  MAX_HISTORY: 50,
  EGG_MINUTES: 5,
  BABY_MINUTES: 60,
  CALL_WINDOW_MINUTES: 15,
  DIRTY_MINUTES: 6 * 60,
  STARVE_MINUTES: 12 * 60,
  ILLNESS_MINUTES: 12 * 60,
  SNACK_DECAY_MINUTES: 120,
  OVERWEIGHT_MARGIN: 20,
  MILLIS_PER_MINUTE: 60_000,
  MINUTES_PER_DAY: 1440,
  /** Catch-up is capped at 45 game days so a long absence can't freeze the page. */
  MAX_CATCH_UP_MINUTES: 45 * 24 * 60,
};

const DAY = C.MINUTES_PER_DAY;

// ------------------------------------------------------------------ editions

export function edition(id) {
  return EDITIONS.find((e) => e.id === id) || EDITIONS[0];
}

export const FORM_SLOTS = ["egg", "baby", "child", "teenGood", "teenBad", "adultBest", "adultOkay", "adultRough", "secret"];

export function formsOf(ed) {
  return FORM_SLOTS.map((slot) => ed.forms[slot]);
}

export function formOf(pet) {
  const ed = edition(pet.editionId);
  return formsOf(ed).find((f) => f.id === pet.formId) || ed.forms.egg;
}

export const stageOf = (pet) => formOf(pet).stage;
export const isAlive = (pet) => pet.ending == null;
export const ageDays = (pet) => (pet.hatchMinute < 0 ? 0 : Math.floor((pet.gameMinutes - pet.hatchMinute) / DAY));
export const minuteOfDay = (pet) => ((pet.clockMinute % DAY) + DAY) % DAY;

export function baseWeight(stage) {
  switch (stage) {
    case Stage.EGG: return 0;
    case Stage.BABY: return 5;
    case Stage.CHILD: return 10;
    case Stage.TEEN: return 20;
    default: return 30;
  }
}

export function isBedtime(mod, rules) {
  return rules.bedtime > rules.wakeTime
    ? mod >= rules.bedtime || mod < rules.wakeTime
    : mod >= rules.bedtime && mod < rules.wakeTime;
}

// ------------------------------------------------------------------ save data

export function newSave() {
  return {
    pet: null,
    lastRealMillis: 0,
    carryMillis: 0,
    paused: false,
    settings: { speed: 1, notifications: false },
    discovered: [],
    history: [],
    version: 1,
  };
}

/** Reads a save, filling in anything missing so older saves keep working. */
export function normalizeSave(raw) {
  const base = newSave();
  if (!raw || typeof raw !== "object") return base;
  const s = { ...base, ...raw, settings: { ...base.settings, ...(raw.settings || {}) } };
  if (!Array.isArray(s.discovered)) s.discovered = [];
  if (!Array.isArray(s.history)) s.history = [];
  return s;
}

function newPet(ed, name, clockMinute, seed) {
  return {
    editionId: ed.id,
    name,
    formId: ed.forms.egg.id,
    gameMinutes: 0,
    clockMinute,
    stageStartMinute: 0,
    hatchMinute: -1,
    adultStartMinute: -1,
    hunger: 0,
    happy: 0,
    discipline: 0,
    weight: 1,
    hungerTimer: 0,
    happyTimer: 0,
    poopTimer: 0,
    poops: 0,
    oldestPoopMinute: -1,
    sick: false,
    dosesNeeded: 0,
    sickMinutes: 0,
    snackCount: 0,
    snackDecayTimer: 0,
    starvingMinutes: 0,
    asleep: false,
    lightsOn: true,
    call: null,
    callStartMinute: 0,
    penalized: [],
    careMistakes: 0,
    stageMistakes: 0,
    adultMistakes: 0,
    disciplineMisses: 0,
    ending: null,
    endedAtMinute: -1,
    evolvedAtMinute: -1,
    rngState: seed >>> 0 || 1,
  };
}

// ------------------------------------------------------------------ the game

export class PetGame {
  constructor(save) {
    this.save = normalizeSave(save);
  }

  get pet() {
    return this.save.pet;
  }

  newEgg(editionId, name, nowMillis, localMinuteOfDay, seed = nowMillis) {
    const ed = edition(editionId);
    const clean = String(name || "").trim().slice(0, C.MAX_NAME) || ed.forms.baby.name;
    this.save.pet = newPet(ed, clean, localMinuteOfDay, mixSeed(seed));
    this.save.lastRealMillis = nowMillis;
    this.save.carryMillis = 0;
    this.save.paused = false;
    this.discover(ed.forms.egg);
  }

  /** Runs the simulation forward to nowMillis. Returns game minutes simulated. */
  advanceTo(nowMillis) {
    const p = this.save.pet;
    const elapsed = nowMillis - this.save.lastRealMillis;
    if (!p || !isAlive(p) || this.save.paused || elapsed <= 0) {
      this.save.lastRealMillis = nowMillis;
      return 0;
    }
    const speed = Math.min(4, Math.max(1, this.save.settings.speed | 0));
    const total = elapsed * speed + this.save.carryMillis;
    let minutes = Math.floor(total / C.MILLIS_PER_MINUTE);
    this.save.carryMillis = total % C.MILLIS_PER_MINUTE;
    this.save.lastRealMillis = nowMillis;
    minutes = Math.min(minutes, C.MAX_CATCH_UP_MINUTES);
    let done = 0;
    while (done < minutes && isAlive(p)) {
      this.step(p);
      done++;
    }
    return done;
  }

  /** Tester tool: jump ahead without waiting. */
  skipMinutes(minutes) {
    const p = this.save.pet;
    if (!p) return;
    for (let i = 0; i < minutes && isAlive(p); i++) this.step(p);
  }

  setPaused(paused, nowMillis) {
    this.advanceTo(nowMillis);
    this.save.paused = paused;
    this.save.lastRealMillis = nowMillis;
  }

  setSpeed(speed, nowMillis) {
    this.advanceTo(nowMillis);
    this.save.settings.speed = Math.min(4, Math.max(1, speed | 0));
  }

  // ---------------------------------------------------------------- simulation

  step(p) {
    const rules = edition(p.editionId).rules;
    p.gameMinutes++;
    p.clockMinute++;

    if (stageOf(p) === Stage.EGG) {
      if (p.gameMinutes - p.stageStartMinute >= C.EGG_MINUTES) this.hatch(p);
      return;
    }

    this.updateSleep(p, rules);

    if (!p.asleep) {
      const baby = stageOf(p) === Stage.BABY;
      if (--p.hungerTimer <= 0) {
        p.hunger = Math.max(0, p.hunger - 1);
        p.hungerTimer = baby ? Math.floor(rules.hungerMinutes / 2) : rules.hungerMinutes;
      }
      if (--p.happyTimer <= 0) {
        p.happy = Math.max(0, p.happy - 1);
        p.happyTimer = baby ? Math.floor(rules.happyMinutes / 2) : rules.happyMinutes;
      }
      if (--p.poopTimer <= 0) {
        if (p.poops < C.MAX_POOPS) p.poops++;
        if (p.oldestPoopMinute < 0) p.oldestPoopMinute = p.gameMinutes;
        p.poopTimer = baby ? Math.floor(rules.poopMinutes / 3) : rules.poopMinutes;
      }
      if (p.snackCount > 0 && --p.snackDecayTimer <= 0) {
        p.snackCount--;
        p.snackDecayTimer = C.SNACK_DECAY_MINUTES;
      }
      if (!p.sick) {
        const overweight = p.weight >= baseWeight(stageOf(p)) + C.OVERWEIGHT_MARGIN;
        const perMinute = (rules.sicknessPerHour / 60) * (overweight ? 4 : 1);
        if (this.roll(p) < perMinute) this.makeSick(p);
      }
      p.starvingMinutes = p.hunger === 0 ? p.starvingMinutes + 1 : 0;
      if (p.sick) p.sickMinutes++;
      if (p.starvingMinutes >= C.STARVE_MINUTES) return this.end(p, "STARVED");
      if (p.sick && p.sickMinutes >= C.ILLNESS_MINUTES) return this.end(p, "ILLNESS");
    }

    if (p.poops > 0 && !p.sick && p.gameMinutes - p.oldestPoopMinute >= C.DIRTY_MINUTES) {
      this.makeSick(p);
      p.oldestPoopMinute = p.gameMinutes;
    }

    this.updateCalls(p, rules);

    if (!p.asleep) {
      this.checkGrowth(p, rules);
      this.checkOldAge(p, rules);
    }
  }

  hatch(p) {
    const ed = edition(p.editionId);
    p.hatchMinute = p.gameMinutes;
    p.hunger = 1;
    p.happy = 1;
    p.weight = baseWeight(Stage.BABY);
    p.hungerTimer = Math.floor(ed.rules.hungerMinutes / 2);
    p.happyTimer = Math.floor(ed.rules.happyMinutes / 2);
    p.poopTimer = Math.floor(ed.rules.poopMinutes / 3);
    this.evolve(p, ed.forms.baby);
  }

  updateSleep(p, rules) {
    const sleeps = stageOf(p) !== Stage.BABY && isBedtime(minuteOfDay(p), rules);
    if (sleeps && !p.asleep) {
      p.asleep = true;
      if (p.call !== CallKind.LIGHTS) p.call = null;
    } else if (!sleeps && p.asleep) {
      p.asleep = false;
      p.lightsOn = true;
      if (p.call === CallKind.LIGHTS) p.call = null;
    }
  }

  updateCalls(p, rules) {
    const needs = [];
    if (p.asleep) {
      if (p.lightsOn) needs.push(CallKind.LIGHTS);
    } else {
      if (p.sick) needs.push(CallKind.SICK);
      if (p.hunger === 0) needs.push(CallKind.HUNGER);
      if (p.happy === 0) needs.push(CallKind.HAPPY);
    }
    p.penalized = p.penalized.filter((k) => needs.includes(k));
    if (p.call != null && p.call !== CallKind.FAKE && !needs.includes(p.call)) p.call = null;

    if (p.call == null) {
      const next = needs.find((k) => !p.penalized.includes(k));
      if (next) {
        this.startCall(p, next);
      } else if (!p.asleep && needs.length === 0 && p.discipline < 4 && this.roll(p) < rules.fakeCallPerHour / 60) {
        this.startCall(p, CallKind.FAKE);
      }
    }

    const call = p.call;
    if (call == null) return;
    if (p.gameMinutes - p.callStartMinute >= C.CALL_WINDOW_MINUTES) {
      if (call === CallKind.FAKE) {
        p.disciplineMisses++;
      } else {
        p.careMistakes++;
        p.stageMistakes++;
        const st = stageOf(p);
        if (st === Stage.ADULT || st === Stage.SECRET) p.adultMistakes++;
        p.penalized = [...p.penalized, call];
      }
      p.call = null;
    }
  }

  startCall(p, kind) {
    p.call = kind;
    p.callStartMinute = p.gameMinutes;
  }

  checkGrowth(p, rules) {
    const f = edition(p.editionId).forms;
    const inStage = p.gameMinutes - p.stageStartMinute;
    switch (p.formId) {
      case f.baby.id:
        if (inStage >= C.BABY_MINUTES) this.evolve(p, f.child);
        break;
      case f.child.id:
        if (inStage >= DAY * rules.stageScale) this.evolve(p, p.stageMistakes <= 3 ? f.teenGood : f.teenBad);
        break;
      case f.teenGood.id:
      case f.teenBad.id:
        if (inStage >= 3 * DAY * rules.stageScale) {
          const m = p.stageMistakes;
          let next;
          if (p.formId === f.teenGood.id) {
            next = m <= 2 && p.discipline >= 3 ? f.adultBest : m <= 5 ? f.adultOkay : f.adultRough;
          } else {
            next = m <= 2 && p.discipline >= 3 ? f.adultOkay : f.adultRough;
          }
          p.adultStartMinute = p.gameMinutes;
          this.evolve(p, next);
        }
        break;
      case f.adultBest.id:
        if (inStage >= 2 * DAY * rules.stageScale && p.stageMistakes === 0 && p.discipline >= 4) this.evolve(p, f.secret);
        break;
    }
  }

  checkOldAge(p, rules) {
    const st = stageOf(p);
    if (st !== Stage.ADULT && st !== Stage.SECRET) return;
    const adultAge = p.adultStartMinute - p.hatchMinute;
    const lifespan = Math.max(
      Math.floor(formOf(p).lifespanDays * rules.lifespanScale * DAY) - p.adultMistakes * (DAY / 2),
      adultAge + DAY,
    );
    if (p.gameMinutes - p.hatchMinute >= lifespan) this.end(p, "OLD_AGE");
  }

  evolve(p, to) {
    p.formId = to.id;
    p.stageStartMinute = p.gameMinutes;
    p.stageMistakes = 0;
    p.weight = Math.max(p.weight, baseWeight(to.stage));
    p.evolvedAtMinute = p.gameMinutes;
    this.discover(to);
  }

  makeSick(p) {
    p.sick = true;
    p.dosesNeeded = 1 + Math.min(1, Math.floor(this.roll(p) * 2));
    p.sickMinutes = 0;
  }

  end(p, ending) {
    p.ending = ending;
    p.endedAtMinute = p.gameMinutes;
    p.call = null;
    this.save.history = [
      ...this.save.history,
      { name: p.name, editionId: p.editionId, formId: p.formId, ageDays: ageDays(p), ending, careMistakes: p.careMistakes },
    ].slice(-C.MAX_HISTORY);
  }

  discover(form) {
    if (!this.save.discovered.includes(form.id)) this.save.discovered = [...this.save.discovered, form.id];
  }

  /** Seeded random number in [0, 1); the state lives in the save so replays are deterministic. */
  roll(p) {
    const [v, next] = mulberry32(p.rngState);
    p.rngState = next;
    return v;
  }

  nextSeed() {
    const p = this.save.pet;
    if (!p) return (Math.random() * 2 ** 32) >>> 0;
    return (this.roll(p) * 2 ** 32) >>> 0;
  }

  // ---------------------------------------------------------------- actions

  withAwakePet(block, needLight = true) {
    const p = this.save.pet;
    if (!p || !isAlive(p)) return R.NO_PET;
    if (stageOf(p) === Stage.EGG) return R.STILL_EGG;
    if (p.asleep) return R.ASLEEP;
    if (needLight && !p.lightsOn) return R.DARK;
    return block(p);
  }

  feedMeal() {
    return this.withAwakePet((p) => {
      if (p.call === CallKind.FAKE) return R.REFUSED;
      if (p.hunger >= C.MAX_HEARTS) return R.FULL;
      p.hunger++;
      p.weight++;
      if (p.call === CallKind.HUNGER) p.call = null;
      return R.ATE_MEAL;
    });
  }

  feedSnack() {
    return this.withAwakePet((p) => {
      if (p.call === CallKind.FAKE) return R.REFUSED;
      p.happy = Math.min(C.MAX_HEARTS, p.happy + 1);
      p.weight += 2;
      p.snackCount++;
      p.snackDecayTimer = C.SNACK_DECAY_MINUTES;
      if (p.call === CallKind.HAPPY) p.call = null;
      if (p.snackCount >= edition(p.editionId).rules.snackLimit && !p.sick) {
        this.makeSick(p);
        p.snackCount = 0;
      }
      return R.ATE_SNACK;
    });
  }

  clean() {
    return this.withAwakePet((p) => {
      if (p.poops === 0) return R.NOTHING_TO_CLEAN;
      p.poops = 0;
      p.oldestPoopMinute = -1;
      return R.CLEANED;
    });
  }

  giveMedicine() {
    return this.withAwakePet((p) => {
      if (!p.sick) return R.NOT_SICK;
      p.dosesNeeded--;
      if (p.dosesNeeded <= 0) {
        p.sick = false;
        p.sickMinutes = 0;
        if (p.call === CallKind.SICK) p.call = null;
        return R.CURED;
      }
      return R.DOSE_GIVEN;
    });
  }

  toggleLights() {
    const p = this.save.pet;
    if (!p || !isAlive(p)) return R.NO_PET;
    p.lightsOn = !p.lightsOn;
    if (!p.lightsOn && p.call === CallKind.LIGHTS) p.call = null;
    return p.lightsOn ? R.LIGHTS_ON : R.LIGHTS_OFF;
  }

  scold() {
    return this.withAwakePet((p) => {
      if (p.call === CallKind.FAKE) {
        p.discipline = Math.min(4, p.discipline + 1);
        p.call = null;
        return R.SCOLD_GOOD;
      }
      p.happy = Math.max(0, p.happy - 1);
      return R.SCOLD_BAD;
    }, false);
  }

  canPlay() {
    return this.withAwakePet((p) => (p.call === CallKind.FAKE ? R.REFUSED : R.OK));
  }

  finishGame(won) {
    return this.withAwakePet((p) => {
      p.weight = Math.max(baseWeight(stageOf(p)), p.weight - 1);
      if (won) {
        p.happy = Math.min(C.MAX_HEARTS, p.happy + edition(p.editionId).rules.gameReward);
        if (p.call === CallKind.HAPPY) p.call = null;
        return R.GAME_WON;
      }
      return R.GAME_LOST;
    });
  }

  /**
   * Game minutes until the pet next starts calling (0 if it is calling now), or null if
   * nothing happens within limitMinutes. Used to schedule a reminder while the page is open.
   */
  minutesUntilNextCall(limitMinutes = 24 * 60) {
    const now = this.save.pet;
    if (!now || !isAlive(now) || this.save.paused) return null;
    if (now.call != null) return 0;
    const sim = new PetGame(JSON.parse(JSON.stringify(this.save)));
    const p = sim.pet;
    for (let minute = 1; minute <= limitMinutes; minute++) {
      sim.step(p);
      if (p.call != null || !isAlive(p)) return minute;
    }
    return null;
  }
}

// ------------------------------------------------------------------ randomness

/** One step of the mulberry32 generator: returns [value in 0..1, next state]. */
export function mulberry32(state) {
  const next = (state + 0x6d2b79f5) >>> 0;
  let t = next;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return [((t ^ (t >>> 14)) >>> 0) / 4294967296, next];
}

function mixSeed(seed) {
  // Fold a (possibly large) number such as Date.now() into 32 bits.
  const n = Number(seed) || 1;
  return ((n % 4294967296) ^ Math.floor(n / 4294967296)) >>> 0;
}

/** A small seeded generator object for the mini-games. */
export class Rng {
  constructor(seed) {
    this.state = seed >>> 0;
  }
  next() {
    const [v, s] = mulberry32(this.state);
    this.state = s;
    return v;
  }
  nextInt(from, until) {
    return from + Math.floor(this.next() * (until - from));
  }
  nextBool() {
    return this.next() < 0.5;
  }
}
