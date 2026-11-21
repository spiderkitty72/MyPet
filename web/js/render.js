// The 48x32 dot-matrix LCD. A port of app/.../ui/LcdScene.kt and LcdView in Components.kt.
import { ICONS, FONT } from "./data.js";
import { Stage, edition, formOf, stageOf, isAlive, ageDays } from "./game.js";

export const LCD_W = 48;
export const LCD_H = 32;
const GROUND = LCD_H - 1;

export const ANIM_MILLIS = {
  EAT_MEAL: 2400, EAT_SNACK: 2400, REFUSE: 1600, CLEAN: 1400, MEDICINE: 1800,
  SCOLD_GOOD: 1600, SCOLD_BAD: 1600, WIN: 2200, LOSE: 1800, EVOLVE: 2600, HATCH: 1600,
};

// ------------------------------------------------------------------ pixel art

const artCache = new Map();

/** Turns text rows into a sprite. '#' lit, 'E' upper eye (off when blinking), 'e' lower eye. */
export function art(rows) {
  let a = artCache.get(rows);
  if (a) return a;
  const width = Math.max(...rows.map((r) => r.length));
  a = { width, height: rows.length, rows };
  artCache.set(rows, a);
  return a;
}

export function isLit(a, x, y, eyesClosed = false) {
  if (x < 0 || y < 0 || x >= a.width || y >= a.height) return false;
  const c = a.rows[y][x];
  return c === "#" || c === "e" || (c === "E" && !eyesClosed);
}

const I = Object.fromEntries(Object.entries(ICONS).map(([k, v]) => [k, art(v)]));
const glyph = (c) => art(FONT[c.toUpperCase()] || FONT["?"]);
const measure = (s) => (s.length ? s.length * 4 - 1 : 0);

// ------------------------------------------------------------------ framebuffer

export class LcdBuffer {
  constructor() {
    this.pixels = new Uint8Array(LCD_W * LCD_H);
  }
  clear(lit = false) {
    this.pixels.fill(lit ? 1 : 0);
  }
  get(x, y) {
    return x >= 0 && y >= 0 && x < LCD_W && y < LCD_H && this.pixels[y * LCD_W + x] === 1;
  }
  set(x, y, lit = true) {
    if (x >= 0 && y >= 0 && x < LCD_W && y < LCD_H) this.pixels[y * LCD_W + x] = lit ? 1 : 0;
  }
  draw(a, x, y, { flip = false, eyesClosed = false, scale = 1, erase = false, skipColumns = 0 } = {}) {
    for (let ay = 0; ay < a.height; ay++) {
      for (let ax = skipColumns; ax < a.width; ax++) {
        const sx = flip ? a.width - 1 - ax : ax;
        if (!isLit(a, sx, ay, eyesClosed)) continue;
        for (let dy = 0; dy < scale; dy++) for (let dx = 0; dx < scale; dx++) {
          this.set(x + ax * scale + dx, y + ay * scale + dy, !erase);
        }
      }
    }
  }
  text(s, x, y, { erase = false, scale = 1 } = {}) {
    let cx = x;
    for (const c of String(s)) {
      this.draw(glyph(c), cx, y, { erase, scale });
      cx += 4 * scale;
    }
  }
  textCentered(s, y, scale = 1) {
    this.text(s, Math.floor((LCD_W - measure(String(s)) * scale) / 2), y, { scale });
  }
  rect(x0, y0, x1, y1, fill) {
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
      if (fill || x === x0 || x === x1 || y === y0 || y === y1) this.set(x, y);
    }
  }
}

// ------------------------------------------------------------------ Star Catch helpers

export const CATCH_BASKET_X = Math.floor((LCD_W - 7) / 2);

export function catchStarX(game, nowMs) {
  const range = LCD_W - I.star.width - 2;
  const period = Math.max(400, Math.floor(1400 / game.speed));
  const t = ((nowMs - game.phaseStartedAt) % (2 * period)) / period;
  const f = t < 1 ? t : 2 - t;
  return 1 + Math.floor(f * range);
}

export function catchIsHit(starX) {
  return Math.abs(starX + Math.floor(I.star.width / 2) - (CATCH_BASKET_X + Math.floor(I.basket.width / 2))) <= 2;
}

// ------------------------------------------------------------------ scenes

const spriteOf = (pet) => art(formOf(pet).sprite);
const groundY = (a) => GROUND - a.height + 1;
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

/**
 * Draws one frame. `frame` advances twice a second and drives walking and blinking;
 * `nowMs` drives smoother one-off animations.
 */
export function drawScene(buf, ui, frame, nowMs) {
  buf.clear();
  const pet = ui.save.pet;
  if (!pet) return;
  if (!isAlive(pet)) return drawEnding(buf, pet, nowMs);

  const anim = ui.anim && nowMs - ui.anim.startedAt < ANIM_MILLIS[ui.anim.kind] ? ui.anim : null;
  if (anim && (anim.kind === "EVOLVE" || anim.kind === "HATCH")) {
    return drawEvolve(buf, pet, anim.kind, nowMs - anim.startedAt, frame);
  }
  if (stageOf(pet) === Stage.EGG) return drawEgg(buf, pet, frame);

  if (!pet.lightsOn) {
    buf.clear(true);
    if (pet.asleep) buf.draw(I.zzz, 34, 4 + (frame % 2), { erase: true });
    return;
  }

  if (ui.menu === "PLAY" && ui.game) return drawGame(buf, pet, ui.game, frame, nowMs);
  if (ui.menu === "STATUS") return drawStatus(buf, pet, ui.statusPage);
  if (ui.menu === "FEED" && !anim) return drawFeedMenu(buf);
  if (ui.menu === "PLAY_PICK") return buf.textCentered("GAMES", 13);
  if (anim) return drawAnim(buf, pet, anim.kind, nowMs - anim.startedAt, frame);
  drawIdle(buf, pet, frame);
}

function drawIdle(buf, pet, frame) {
  const a = spriteOf(pet);
  const poopArea = pet.poops > 0 ? 12 : 0;
  const minX = 1;
  const maxX = Math.max(minX, LCD_W - poopArea - a.width - 1);

  for (let i = 0; i < pet.poops; i++) {
    const py = GROUND - I.poop.height + 1 - i * (I.poop.height + 1);
    buf.draw(I.poop, LCD_W - I.poop.width - 1, py);
    if ((frame + i) % 2 === 0) buf.draw(I.stink, LCD_W - I.poop.width - 6, py - 1);
  }

  if (pet.asleep) {
    const x = Math.floor((minX + maxX) / 2);
    buf.draw(a, x, groundY(a), { eyesClosed: true });
    buf.draw(I.zzz, Math.min(x + a.width + 1, LCD_W - 9), 2 + (frame % 2));
    return;
  }

  const range = maxX - minX;
  let x;
  let flip = false;
  let hop = 0;
  if (pet.sick || range <= 0) {
    x = Math.floor((minX + maxX) / 2);
  } else {
    const period = range + 3;
    const t = (frame * 2) % (2 * period);
    const leg = Math.floor(t / period);
    const pos = Math.min(t % period, range);
    x = leg === 0 ? minX + pos : maxX - pos;
    flip = leg === 1;
    hop = frame % 2 === 0 && pos >= 1 && pos < range ? 1 : 0;
  }
  const blink = frame % 9 === 0 || (pet.sick && frame % 3 === 0);
  buf.draw(a, x, groundY(a) - hop, { flip, eyesClosed: blink });

  if (pet.sick) buf.draw(I.sick, clamp(x + Math.floor(a.width / 2) - 4, 0, LCD_W - 8), Math.max(0, groundY(a) - 8));
  if (pet.call != null && frame % 2 === 0) buf.draw(I.bang, 1, 1);
}

function drawEgg(buf, pet, frame) {
  const a = spriteOf(pet);
  const wobble = [0, -1, 0, 1][frame % 4];
  buf.draw(a, Math.floor((LCD_W - a.width) / 2) + wobble, groundY(a));
}

function drawEvolve(buf, pet, kind, elapsed, frame) {
  const a = spriteOf(pet);
  const x = Math.floor((LCD_W - a.width) / 2);
  if (elapsed < 1200) {
    const on = Math.floor(elapsed / 150) % 2 === 0;
    if (kind === "HATCH") {
      const egg = art(edition(pet.editionId).forms.egg.sprite);
      buf.draw(egg, Math.floor((LCD_W - egg.width) / 2) + (on ? 1 : -1), groundY(egg));
    } else {
      buf.clear(on);
      buf.draw(a, x, groundY(a), { erase: on });
    }
    return;
  }
  buf.draw(a, x, groundY(a), { eyesClosed: frame % 2 === 0 });
  const s = I.sparkle;
  if (frame % 2 === 0) {
    buf.draw(s, x - 7, 8);
    buf.draw(s, x + a.width + 2, 12);
  } else {
    buf.draw(s, x - 6, 14);
    buf.draw(s, x + a.width + 1, 6);
  }
}

function drawStatus(buf, pet, page) {
  const hearts = (n) => {
    const x0 = Math.floor((LCD_W - (4 * 7 + 3 * 2)) / 2);
    for (let i = 0; i < 4; i++) buf.draw(i < n ? I.heartFull : I.heartEmpty, x0 + i * 9, 17);
  };
  switch (page) {
    case 0: buf.textCentered("HUNGER", 5); hearts(pet.hunger); break;
    case 1: buf.textCentered("HAPPY", 5); hearts(pet.happy); break;
    case 2: {
      buf.textCentered("TRAINING", 5);
      buf.rect(4, 16, 43, 23, false);
      const filled = pet.discipline * 9;
      if (filled > 0) buf.rect(6, 18, 5 + filled, 21, true);
      break;
    }
    case 3:
      buf.textCentered(`AGE ${ageDays(pet)}`, 5);
      buf.textCentered(`WT ${pet.weight}G`, 18);
      break;
    default:
      buf.textCentered(pet.name.slice(0, 11), 5);
      buf.textCentered(formOf(pet).name.slice(0, 11), 18);
  }
}

function drawFeedMenu(buf) {
  buf.draw(I.meal, 4, 5, { scale: 2 });
  buf.draw(I.snack, 30, 3, { scale: 2 });
  buf.text("MEAL", 4, 24);
  buf.text("SNACK", 26, 24);
}

function drawAnim(buf, pet, kind, elapsed, frame) {
  const a = spriteOf(pet);
  const center = Math.floor((LCD_W - a.width) / 2);
  const gy = groundY(a);
  switch (kind) {
    case "EAT_MEAL":
    case "EAT_SNACK": {
      const food = kind === "EAT_MEAL" ? I.meal : I.snack;
      const bite = Math.min(3, Math.floor(elapsed / 600));
      const chew = Math.floor(elapsed / 200) % 2 === 0;
      buf.draw(a, 4, gy, { eyesClosed: chew && bite > 0 });
      if (bite < 3) {
        buf.draw(food, 4 + a.width + 3, GROUND - food.height + 1, { skipColumns: Math.floor((bite * food.width) / 3) });
      } else {
        buf.draw(I.note, 4 + a.width + 4, 6 + (frame % 2));
      }
      break;
    }
    case "REFUSE": {
      const flip = Math.floor(elapsed / 200) % 2 === 0;
      buf.draw(a, center + (flip ? -1 : 1), gy, { flip });
      buf.textCentered("NO!", 2);
      break;
    }
    case "CLEAN": {
      drawIdle(buf, pet, frame);
      const lineX = LCD_W - 1 - Math.floor((elapsed * LCD_W) / ANIM_MILLIS.CLEAN);
      for (let x = lineX; x < LCD_W; x++) for (let y = 0; y < LCD_H; y++) buf.set(x, y, false);
      for (let y = 0; y < LCD_H; y++) buf.set(lineX, y, true);
      if (lineX + 2 < LCD_W) buf.draw(I.sparkle, Math.min(lineX + 3, LCD_W - 5), 4 + (frame % 2) * 10);
      break;
    }
    case "MEDICINE": {
      buf.draw(a, center, gy, { eyesClosed: true });
      const travel = Math.min(14, Math.floor(elapsed / 60));
      if (elapsed < 900) {
        buf.draw(I.pill, LCD_W - 8 - travel, 4 + Math.floor(travel / 2));
      } else {
        const on = Math.floor(elapsed / 200) % 2 === 0;
        buf.draw(I.sparkle, center - 6, on ? 6 : 10);
        buf.draw(I.sparkle, center + a.width + 1, on ? 10 : 6);
      }
      break;
    }
    case "SCOLD_GOOD":
    case "SCOLD_BAD": {
      const shake = kind === "SCOLD_BAD" && Math.floor(elapsed / 150) % 2 === 0 ? 1 : 0;
      buf.draw(a, center + shake, gy, { eyesClosed: true });
      buf.draw(I.angry, center + a.width + 1, Math.max(0, gy - 6));
      if (kind === "SCOLD_BAD") buf.draw(I.heartEmpty, center - 9, gy);
      else buf.draw(I.bang, center - 4, gy);
      break;
    }
    case "WIN": {
      const jump = Math.floor(Math.abs(Math.sin(elapsed / 160)) * 5);
      buf.draw(a, center, gy - jump);
      buf.draw(I.note, 2, 6 + (frame % 2));
      buf.draw(I.note, LCD_W - 7, 8 - (frame % 2));
      buf.textCentered("WIN!", 1);
      break;
    }
    case "LOSE":
      buf.draw(a, center, gy, { eyesClosed: true });
      buf.textCentered("LOSE", 1);
      break;
    default:
      drawEvolve(buf, pet, kind, elapsed, frame);
  }
}

function drawGame(buf, pet, game, frame, nowMs) {
  const round = Math.min(5, game.round + (game.phase === "WAITING" ? 1 : 0));
  buf.text(`${round}/5`, 1, 1);
  buf.text(`${game.wins}`, LCD_W - 4, 1);
  buf.draw(I.heartFull, LCD_W - 12, 0);
  const result = game.phase === "REVEAL" ? (game.lastCorrect ? "YES!" : "NO") : null;

  if (game.kind === "WHICH_WAY") {
    const a = spriteOf(pet);
    const center = Math.floor((LCD_W - a.width) / 2);
    if (game.phase === "WAITING") {
      buf.draw(a, center, groundY(a), { eyesClosed: frame % 6 === 0 });
      if (frame % 2 === 0) {
        buf.draw(I.arrowLeft, 1, 20);
        buf.draw(I.arrowRight, LCD_W - 6, 20);
      }
    } else {
      const left = game.petTurnedLeft === true;
      buf.draw(a, center + (left ? -6 : 6), groundY(a), { flip: left });
      buf.textCentered(result, 8);
    }
  } else if (game.kind === "HI_LO") {
    if (game.phase === "WAITING") {
      buf.textCentered(`${game.shown}`, 9, 3);
      if (frame % 2 === 0) {
        buf.text("HI", 1, 14);
        buf.text("LO", LCD_W - 8, 14);
      }
    } else {
      buf.text(`${game.previous}`, 4, 16);
      buf.draw(I.arrowRight, 10, 16);
      buf.textCentered(`${game.shown}`, 12, 3);
      buf.text(result, LCD_W - measure(result) - 1, 16);
    }
  } else {
    buf.draw(I.basket, CATCH_BASKET_X, GROUND - I.basket.height + 1);
    if (game.phase === "WAITING") {
      buf.draw(I.star, catchStarX(game, nowMs), 8);
    } else {
      const hit = game.lastCorrect === true;
      const sx = hit ? CATCH_BASKET_X : CATCH_BASKET_X + (frame % 2 === 0 ? 12 : -12);
      const fall = Math.min(12, Math.floor((nowMs - game.phaseStartedAt) / 40));
      buf.draw(I.star, sx, Math.min(8 + fall, GROUND - 9));
      buf.textCentered(result, 1);
    }
  }
}

function drawEnding(buf, pet, nowMs) {
  if (edition(pet.editionId).rules.gentleEndings) {
    const a = spriteOf(pet);
    const span = LCD_W + a.width + 10;
    const x = (Math.floor(nowMs / 120) % span) - a.width - 8;
    buf.draw(I.bundle, x, GROUND - I.bundle.height + 1);
    buf.draw(a, x + 8, groundY(a), { eyesClosed: Math.floor(nowMs / 500) % 6 === 0 });
  } else {
    const bob = Math.trunc(Math.sin(nowMs / 400) * 3);
    const x = Math.floor((LCD_W - I.spirit.width) / 2);
    buf.draw(I.halo, x, 6 + bob);
    buf.draw(I.spirit, x, 10 + bob);
    buf.draw(I.sparkle, x - 10, 14 - bob);
    buf.draw(I.sparkle, x + 14, 18 + bob);
  }
}

// ------------------------------------------------------------------ painting

/** Paints the framebuffer onto a canvas: faint unlit cells, soft pixel shadows, then lit dots. */
export function paintLcd(ctx, buf, w, h, lcdColor, inkColor) {
  ctx.clearRect(0, 0, w, h);
  const grad = ctx.createLinearGradient(0, 0, 0, h);
  grad.addColorStop(0, lcdColor);
  grad.addColorStop(1, mix(lcdColor, "#000000", 0.08));
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, w, h);
  const margin = w * 0.03;
  const cell = (w - margin * 2) / LCD_W;
  const top = (h - cell * LCD_H) / 2;
  const dot = cell * 0.86;
  const off = cell * 0.18;
  ctx.fillStyle = withAlpha(inkColor, 0.07);
  for (let y = 0; y < LCD_H; y++) for (let x = 0; x < LCD_W; x++) {
    if (!buf.get(x, y)) ctx.fillRect(margin + x * cell, top + y * cell, dot, dot);
  }
  ctx.fillStyle = withAlpha(inkColor, 0.16);
  for (let y = 0; y < LCD_H; y++) for (let x = 0; x < LCD_W; x++) {
    if (buf.get(x, y)) ctx.fillRect(margin + x * cell + off, top + y * cell + off, dot, dot);
  }
  ctx.fillStyle = inkColor;
  for (let y = 0; y < LCD_H; y++) for (let x = 0; x < LCD_W; x++) {
    if (buf.get(x, y)) ctx.fillRect(margin + x * cell, top + y * cell, dot, dot);
  }
}

/** Draws a sprite in one colour, scaled to fit a canvas. */
export function paintIcon(canvas, rows, color, eyesClosed = false) {
  const a = art(rows);
  const ctx = canvas.getContext("2d");
  const w = canvas.width;
  const h = canvas.height;
  ctx.clearRect(0, 0, w, h);
  const cell = Math.min(w / a.width, h / a.height);
  const ox = (w - cell * a.width) / 2;
  const oy = (h - cell * a.height) / 2;
  ctx.fillStyle = color;
  for (let y = 0; y < a.height; y++) for (let x = 0; x < a.width; x++) {
    if (isLit(a, x, y, eyesClosed)) ctx.fillRect(ox + x * cell, oy + y * cell, cell * 0.92, cell * 0.92);
  }
}

// ------------------------------------------------------------------ colours

function hex(c) {
  const n = parseInt(c.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

export function mix(a, b, t) {
  const x = hex(a);
  const y = hex(b);
  const out = x.map((v, i) => Math.round(v + (y[i] - v) * t));
  return "#" + out.map((v) => v.toString(16).padStart(2, "0")).join("");
}

export function withAlpha(c, alpha) {
  const [r, g, b] = hex(c);
  return `rgba(${r},${g},${b},${alpha})`;
}

export function brightness(c) {
  const [r, g, b] = hex(c).map((v) => v / 255);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
