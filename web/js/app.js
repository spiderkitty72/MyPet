// The page controller: a port of PetViewModel.kt plus the Compose screens, in plain DOM.
import { EDITIONS, ACTION_ICONS } from "./data.js";
import {
  PetGame, R, C, CallKind, ENDINGS, GAMES, Stage, STAGE_LABEL,
  edition, formOf, formsOf, stageOf, isAlive, minuteOfDay,
} from "./game.js";
import { MiniGame } from "./minigame.js";
import {
  LcdBuffer, drawScene, paintLcd, paintIcon, catchStarX, catchIsHit, mix, withAlpha, brightness,
} from "./render.js";
import { loadSave, storeSave } from "./storage.js";

const MESSAGE_MILLIS = 3500;
const REVEAL_MILLIS = 1300;
const STATUS_PAGES = 5;

const $ = (sel) => document.querySelector(sel);
const now = () => Date.now();
const localMinute = () => {
  const d = new Date();
  return d.getHours() * 60 + d.getMinutes();
};

// ------------------------------------------------------------------ state

const game = new PetGame(loadSave());
const ui = {
  screen: game.pet ? "DEVICE" : "START",
  menu: "NONE",
  statusPage: 0,
  game: null,
  anim: null,
  message: null,
  messageAt: 0,
  save: game.save,
};
let mini = null;
let revealTimer = null;
let lastEvolvedSeen = game.pet ? game.pet.evolvedAtMinute : -1;
let lastCallKey = null;
let selectedEdition = EDITIONS[0].id;

function persist() {
  storeSave(game.save);
}

/** Notices growth since the last frame and queues the hatch/evolve flash. */
function checkEvolution() {
  const pet = game.pet;
  if (pet && pet.evolvedAtMinute !== lastEvolvedSeen) {
    lastEvolvedSeen = pet.evolvedAtMinute;
    ui.anim = { kind: stageOf(pet) === Stage.BABY ? "HATCH" : "EVOLVE", startedAt: now() };
  }
}

function tick() {
  const wasAlive = game.pet && isAlive(game.pet);
  const minutes = game.advanceTo(now());
  if (minutes > 0) {
    if (wasAlive && !isAlive(game.pet)) {
      mini = null;
      ui.menu = "NONE";
      ui.game = null;
    }
    checkEvolution();
    persist();
    // The start and collection screens hold form input and scroll position; only the
    // device screen needs to follow the clock.
    if (ui.screen === "DEVICE") render();
    else updateBadge();
  }
}

// ------------------------------------------------------------------ text

function resultText(r) {
  const name = game.pet ? game.pet.name : "Your pet";
  switch (r) {
    case R.ATE_MEAL: return "Yum! A proper meal.";
    case R.ATE_SNACK: return "A treat! So happy.";
    case R.FULL: return `${name} is full.`;
    case R.REFUSED: return `${name} refuses! Is it testing you?`;
    case R.CLEANED: return "All clean.";
    case R.NOTHING_TO_CLEAN: return "Already spotless.";
    case R.DOSE_GIVEN: return "One more dose needed.";
    case R.CURED: return `${name} feels better!`;
    case R.NOT_SICK: return `${name} isn't sick.`;
    case R.LIGHTS_ON: return "Lights on.";
    case R.LIGHTS_OFF: return "Lights off.";
    case R.SCOLD_GOOD: return `${name} learned a lesson.`;
    case R.SCOLD_BAD: return `That wasn't fair... ${name} is sad.`;
    case R.GAME_WON: return `You won! ${name} is delighted.`;
    case R.GAME_LOST: return "You lost. Still, that was fun.";
    case R.ASLEEP: return `Shh, ${name} is asleep.`;
    case R.DARK: return "It's too dark. Turn the light on.";
    case R.STILL_EGG: return "The egg is still warming up.";
    default: return null;
  }
}

function callText(pet) {
  switch (pet.call) {
    case CallKind.HUNGER: return `${pet.name} is hungry!`;
    case CallKind.HAPPY: return `${pet.name} wants attention!`;
    case CallKind.SICK: return `${pet.name} feels sick!`;
    case CallKind.LIGHTS: return `${pet.name} is sleepy. Lights off?`;
    case CallKind.FAKE: return `${pet.name} is calling...`;
    default: return null;
  }
}

function endingText(pet) {
  const e = ENDINGS[pet.ending];
  if (!e) return "";
  return edition(pet.editionId).rules.gentleEndings ? e.gentle : e.classic;
}

function captionText() {
  const pet = game.pet;
  if (!pet) return "";
  const recent = ui.message && now() - ui.messageAt < MESSAGE_MILLIS;
  if (!isAlive(pet)) return endingText(pet);
  if (recent) return ui.message;
  if (game.save.paused) return "Paused.";
  if (stageOf(pet) === Stage.EGG) return "Something is stirring...";
  if (ui.menu === "STATUS") return "Tap the screen for the next page.";
  if (ui.menu === "PLAY" && ui.game) return GAMES[ui.game.kind].help;
  const call = callText(pet);
  if (call) return call;
  if (pet.asleep) return pet.lightsOn ? `${pet.name} fell asleep.` : `Sweet dreams, ${pet.name}.`;
  return `${pet.name} the ${formOf(pet).name}`;
}

// ------------------------------------------------------------------ actions

function say(text) {
  ui.message = text;
  ui.messageAt = now();
  render();
}

function applyResult(r, anim, closeMenu = true) {
  const quiet = [R.NOTHING_TO_CLEAN, R.NOT_SICK, R.ASLEEP, R.DARK, R.STILL_EGG, R.NO_PET];
  persist();
  if (closeMenu) ui.menu = "NONE";
  if (anim && !quiet.includes(r)) ui.anim = { kind: anim, startedAt: now() };
  ui.message = resultText(r);
  ui.messageAt = now();
  render();
}

function precheck(r, ok) {
  if (r === R.OK) ok();
  else applyResult(r, r === R.REFUSED ? "REFUSE" : null);
}

function onAction(action) {
  const pet = game.pet;
  if (!pet || !isAlive(pet) || ui.menu === "PLAY") return;
  buzz(8);
  switch (action) {
    case "STATUS":
      if (stageOf(pet) === Stage.EGG) return say(resultText(R.STILL_EGG));
      ui.menu = ui.menu === "STATUS" ? "NONE" : "STATUS";
      ui.statusPage = 0;
      return render();
    case "FEED": {
      const r = game.canPlay();
      return precheck(r === R.REFUSED ? R.OK : r, () => {
        ui.menu = ui.menu === "FEED" ? "NONE" : "FEED";
        render();
      });
    }
    case "PLAY":
      return precheck(game.canPlay(), () => {
        const games = edition(pet.editionId).rules.games;
        if (games.length === 1) startGame(games[0]);
        else {
          ui.menu = "PLAY_PICK";
          render();
        }
      });
    case "CLEAN": return applyResult(game.clean(), "CLEAN");
    case "MEDICINE": return applyResult(game.giveMedicine(), "MEDICINE");
    case "LIGHT": return applyResult(game.toggleLights(), null);
    case "SCOLD": {
      const r = game.scold();
      return applyResult(r, r === R.SCOLD_GOOD ? "SCOLD_GOOD" : "SCOLD_BAD");
    }
    case "PAUSE": return togglePause();
  }
}

function feed(meal) {
  const r = meal ? game.feedMeal() : game.feedSnack();
  const anim = r === R.ATE_MEAL ? "EAT_MEAL" : r === R.ATE_SNACK ? "EAT_SNACK" : r === R.REFUSED || r === R.FULL ? "REFUSE" : null;
  applyResult(r, anim, ![R.ATE_MEAL, R.ATE_SNACK, R.FULL].includes(r));
}

function closeMenu() {
  ui.menu = "NONE";
  render();
}

function nextStatusPage() {
  ui.statusPage = (ui.statusPage + 1) % STATUS_PAGES;
  render();
}

function togglePause() {
  const paused = !game.save.paused;
  game.setPaused(paused, now());
  persist();
  say(paused ? "Paused. Time stands still." : "Welcome back!");
}

// ------------------------------------------------------------------ mini-games

function gameUi(g, phase, previous) {
  return {
    kind: g.kind, round: g.round, wins: g.wins, phase, lastCorrect: g.lastCorrect,
    petTurnedLeft: g.petTurnedLeft, shown: g.shown, previous, speed: g.speed, phaseStartedAt: now(),
  };
}

function startGame(kind) {
  if (game.canPlay() !== R.OK) return applyResult(game.canPlay(), "REFUSE");
  mini = new MiniGame(kind, game.nextSeed());
  ui.menu = "PLAY";
  ui.game = gameUi(mini, "WAITING", -1);
  render();
}

/** choice: 0 = left/lower/miss, 1 = right/higher/hit. */
function gameInput(choice) {
  const g = mini;
  if (!g || !ui.game || ui.game.phase !== "WAITING" || g.finished) return;
  buzz(8);
  const before = g.shown;
  if (g.kind === "WHICH_WAY") g.guessWay(choice === 0);
  else if (g.kind === "HI_LO") g.guessHigher(choice === 1);
  else g.submitCatch(choice === 1);
  ui.game = gameUi(g, "REVEAL", before);
  render();
  clearTimeout(revealTimer);
  revealTimer = setTimeout(() => {
    if (mini !== g) return;
    if (g.finished) {
      mini = null;
      const r = game.finishGame(g.won);
      persist();
      ui.menu = "NONE";
      ui.game = null;
      ui.anim = { kind: g.won ? "WIN" : "LOSE", startedAt: now() };
      say(resultText(r));
    } else {
      ui.game = gameUi(g, "WAITING", before);
      render();
    }
  }, REVEAL_MILLIS);
}

function quitGame() {
  clearTimeout(revealTimer);
  mini = null;
  ui.menu = "NONE";
  ui.game = null;
  render();
}

// ------------------------------------------------------------------ navigation

function show(screen) {
  ui.screen = screen;
  ui.menu = "NONE";
  render();
  window.scrollTo(0, 0);
}

function startNewPet(editionId, name) {
  game.newEgg(editionId, name, now(), localMinute());
  lastEvolvedSeen = game.pet.evolvedAtMinute;
  mini = null;
  Object.assign(ui, { menu: "NONE", game: null, anim: null, message: null });
  persist();
  show("DEVICE");
}

// ------------------------------------------------------------------ helpers

function buzz(ms) {
  try {
    if (navigator.vibrate) navigator.vibrate(ms);
  } catch {
    /* not supported */
  }
}

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") node.className = v;
    else if (k === "style") Object.assign(node.style, v);
    else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else if (v === true) node.setAttribute(k, "");
    else if (v !== false && v != null) node.setAttribute(k, v);
  }
  for (const c of children.flat()) if (c != null) node.append(c);
  return node;
}

function iconCanvas(rows, color, px, cls = "pix") {
  const ratio = Math.min(window.devicePixelRatio || 1, 3);
  const c = el("canvas", { class: cls, width: Math.round(px * ratio), height: Math.round(px * ratio) });
  c.style.width = c.style.height = `${px}px`;
  paintIcon(c, rows, color);
  return c;
}

const hourLabel = (minutes) => {
  const h = Math.floor(minutes / 60);
  return h === 0 ? "12am" : h < 12 ? `${h}am` : h === 12 ? "12pm" : `${h - 12}pm`;
};

// ------------------------------------------------------------------ screens

function render() {
  const app = $("#app");
  if (ui.screen === "DEVICE" && game.pet) {
    if (!app.querySelector(".device")) {
      app.replaceChildren(buildDevice());
    }
    updateDevice();
  } else if (ui.screen === "COLLECTION") {
    app.replaceChildren(buildCollection());
  } else {
    app.replaceChildren(buildStart());
  }
  updateBadge();
}

// ---- start screen

function buildStart() {
  const hasLivePet = game.pet && isAlive(game.pet);
  const ed = edition(selectedEdition);
  const nameInput = el("input", {
    id: "pet-name", type: "text", maxlength: C.MAX_NAME, placeholder: "Pet name (optional)",
    autocomplete: "off", "aria-label": "Pet name (optional)",
    oninput: (e) => {
      e.target.value = e.target.value.replace(/[^\p{L}\p{N} ]/gu, "").slice(0, C.MAX_NAME);
    },
  });
  nameInput.style.borderColor = ed.shellColor;
  return el("div", { class: "page" },
    el("header", { class: "page-head" },
      el("div", {},
        el("h1", {}, "MyPet"),
        el("p", { class: "muted" }, "Choose an edition and hatch your egg.")),
      game.save.discovered.length ? el("button", { class: "link", onclick: () => show("COLLECTION") }, "Collection") : null),
    el("div", { class: "editions" }, EDITIONS.map((e) => editionCard(e, e.id === selectedEdition))),
    el("div", { class: "hatch" },
      nameInput,
      el("div", { class: "row" },
        hasLivePet ? wideButton("Cancel", ed.shellAccent, "#b4acbf", () => show("DEVICE")) : null,
        wideButton(`Hatch a ${ed.name} egg`, ed.shellAccent, ed.shellColor, () => startNewPet(ed.id, nameInput.value), 2)),
      hasLivePet ? el("p", { class: "warn" }, `Hatching a new egg will say goodbye to ${game.pet.name}.`) : null),
    footer());
}

function editionCard(e, selected) {
  const dark = brightness(e.shellColor) < 0.4;
  const text = dark ? "#f1e9d8" : mix(e.shellAccent, "#000000", 0.35);
  const r = e.rules;
  const grow = 1 + 3 * r.stageScale;
  const facts = [
    `Adult in ~${grow} days`,
    `Bed ${hourLabel(r.bedtime)}-${hourLabel(r.wakeTime)}`,
    r.gentleEndings ? "Gentle endings" : "Classic endings",
    r.games.map((g) => GAMES[g].title).join(" / "),
  ];
  return el("button", {
    class: "edition" + (selected ? " selected" : ""),
    "aria-pressed": selected ? "true" : "false",
    style: { background: e.shellColor, color: text, borderColor: selected ? e.shellAccent : withAlpha(e.shellAccent, 0.3) },
    onclick: () => {
      selectedEdition = e.id;
      const name = $("#pet-name")?.value || "";
      render();
      const input = $("#pet-name");
      if (input) input.value = name;
    },
  },
  el("div", { class: "edition-top" },
    el("div", { class: "edition-art", style: { background: e.lcdColor } },
      iconCanvas(e.forms.egg.sprite, e.inkColor, 34),
      iconCanvas(e.forms.child.sprite, e.inkColor, 30),
      iconCanvas(e.forms.adultBest.sprite, e.inkColor, 40)),
    el("div", { class: "edition-name" },
      el("strong", { style: { color: e.shellAccent } }, e.name),
      el("span", {}, e.tagline)),
    selected ? el("span", { class: "tick", style: { color: dark ? "#f1e9d8" : e.shellAccent } }, "✓") : null),
  el("p", { class: "edition-desc" }, e.description),
  el("p", { class: "edition-facts" }, facts.join("  ·  ")));
}

function wideButton(label, accent, shell, onclick, grow = 1) {
  return el("button", {
    class: "wide",
    style: { color: accent, background: mix(shell, "#ffffff", 0.35), borderColor: withAlpha(accent, 0.35), flexGrow: grow },
    onclick,
  }, label);
}

// ---- collection screen

function buildCollection() {
  const found = new Set(game.save.discovered);
  const total = EDITIONS.length * 9;
  const back = game.pet ? "DEVICE" : "START";
  return el("div", { class: "page" },
    el("header", { class: "page-head" },
      el("div", {},
        el("h1", {}, "Collection"),
        el("p", { class: "muted" }, `${found.size} of ${total} forms found`)),
      el("button", { class: "link", onclick: () => show(back) }, "Back")),
    EDITIONS.map((e) => el("section", { class: "forms", style: { background: e.shellColor } },
      el("h2", { style: { color: e.shellAccent } }, e.name),
      el("div", { class: "form-grid" }, formsOf(e).map((f) => {
        const known = found.has(f.id);
        return el("div", { class: "form-cell", style: { background: e.lcdColor, color: e.inkColor } },
          iconCanvas(f.sprite, known ? e.inkColor : withAlpha(e.inkColor, 0.12), 44),
          el("span", { class: "form-name" }, known ? f.name : "???"),
          el("span", { class: "form-stage" }, STAGE_LABEL[f.stage]));
      })))),
    game.save.history.length ? el("h2", { class: "past-title" }, "Past pets") : null,
    [...game.save.history].reverse().map((h) => {
      const e = edition(h.editionId);
      const f = formsOf(e).find((x) => x.id === h.formId);
      const ending = ENDINGS[h.ending] ? (e.rules.gentleEndings ? ENDINGS[h.ending].gentle : ENDINGS[h.ending].classic) : "";
      return el("div", { class: "past" },
        f ? iconCanvas(f.sprite, e.lcdColor, 32) : null,
        el("div", {},
          el("div", {}, `${h.name} · ${f ? f.name : "?"}`),
          el("div", { class: "muted small" }, `${e.name} · age ${h.ageDays} · ${h.careMistakes} care mistakes`),
          el("div", { class: "muted small" }, ending)));
    }),
    footer());
}

function footer() {
  return el("p", { class: "footer muted small" }, "MyPet is an original game. All characters and art are made for this app.");
}

// ---- device screen

const ACTIONS = [
  ["STATUS", "status", "Status"], ["FEED", "feed", "Feed"], ["PLAY", "play", "Play"], ["CLEAN", "clean", "Clean"],
  ["MEDICINE", "medicine", "Medicine"], ["LIGHT", "light", "Light"], ["SCOLD", "scold", "Scold"], ["PAUSE", "pause", "Pause"],
];

let lcdCanvas = null;
let lcdBuffer = new LcdBuffer();
let deviceKey = "";

function buildDevice() {
  deviceKey = "";
  lcdCanvas = el("canvas", { class: "lcd", "aria-label": "Pet screen", role: "img", onclick: () => { if (ui.menu === "STATUS") nextStatusPage(); } });
  return el("div", { class: "device-page" },
    el("header", { class: "top" },
      el("span", { class: "brand" }, "MyPet"),
      el("span", { class: "spacer" }),
      el("button", { class: "link install hidden", onclick: promptInstall }, "Install"),
      el("button", { class: "link", onclick: () => show("COLLECTION") }, "Collection"),
      el("button", { class: "link", onclick: openSettings }, "Settings")),
    el("div", { class: "device" },
      el("div", { class: "shell-head" },
        el("strong", { class: "ed-name" }),
        el("span", { class: "ed-tag" }),
        el("span", { class: "spacer" }),
        el("span", { class: "led" }),
        el("span", { class: "clock" })),
      el("div", { class: "bezel" }, lcdCanvas),
      el("p", { class: "caption", "aria-live": "polite" }),
      el("div", { class: "controls" })),
    el("p", { class: "speed muted small" }));
}

function updateDevice() {
  const pet = game.pet;
  const e = edition(pet.editionId);
  const device = $(".device");
  device.style.setProperty("--shell", e.shellColor);
  device.style.setProperty("--shell-hi", mix(e.shellColor, "#ffffff", 0.25));
  device.style.setProperty("--shell-lo", mix(e.shellColor, "#000000", 0.12));
  device.style.setProperty("--shell-edge", mix(e.shellColor, "#000000", 0.2));
  device.style.setProperty("--accent", e.shellAccent);
  device.style.setProperty("--accent-soft", withAlpha(e.shellAccent, 0.35));
  device.style.setProperty("--bezel", mix(e.shellAccent, "#000000", 0.45));
  device.style.setProperty("--caption", mix(e.shellAccent, "#000000", 0.25));
  device.style.setProperty("--face", mix(e.shellColor, "#ffffff", 0.35));
  $(".ed-name").textContent = e.name.toUpperCase();
  $(".ed-tag").textContent = e.tagline;
  $(".led").classList.toggle("on", isAlive(pet) && pet.call != null);
  const m = minuteOfDay(pet);
  $(".clock").textContent = `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
  $(".caption").textContent = captionText();
  const speed = game.save.settings.speed;
  $(".speed").textContent = game.save.paused ? "Paused: time is frozen" : speed === 1 ? "Real time: 1 pet day = 1 day" : `${speed}x speed`;
  $(".install").classList.toggle("hidden", !installEvent);

  // Rebuild the buttons only when what they show changes.
  const key = [isAlive(pet), ui.menu, game.save.paused, ui.game ? ui.game.kind : "", pet.editionId].join("|");
  if (key !== deviceKey) {
    deviceKey = key;
    $(".controls").replaceChildren(buildControls(pet, e));
  }

  // Vibrate once when a new call starts, like the Android app.
  const callKey = pet.call ? `${pet.call}@${pet.callStartMinute}` : null;
  if (callKey && callKey !== lastCallKey) buzz([60, 80, 60]);
  lastCallKey = callKey;
}

function buildControls(pet, e) {
  const accent = e.shellAccent;
  const shell = e.shellColor;
  const row = (...kids) => el("div", { class: "row" }, ...kids);
  if (!isAlive(pet)) {
    return row(wideButton("New egg", accent, shell, () => show("START")), wideButton("Collection", accent, shell, () => show("COLLECTION")));
  }
  switch (ui.menu) {
    case "FEED":
      return row(
        wideButton("Meal", accent, shell, () => feed(true)),
        wideButton("Snack", accent, shell, () => feed(false)),
        wideButton("Back", accent, shell, closeMenu, 0.7));
    case "PLAY_PICK":
      return el("div", { class: "stack" },
        row(...e.rules.games.map((k) => wideButton(GAMES[k].title, accent, shell, () => startGame(k)))),
        row(wideButton("Back", accent, shell, closeMenu)));
    case "PLAY": {
      const waiting = () => ui.game && ui.game.phase === "WAITING";
      let buttons;
      if (ui.game.kind === "WHICH_WAY") {
        buttons = [wideButton("< Left", accent, shell, () => waiting() && gameInput(0)), wideButton("Right >", accent, shell, () => waiting() && gameInput(1))];
      } else if (ui.game.kind === "HI_LO") {
        buttons = [wideButton("Higher", accent, shell, () => waiting() && gameInput(1)), wideButton("Lower", accent, shell, () => waiting() && gameInput(0))];
      } else {
        buttons = [wideButton("STOP!", accent, shell, () => {
          if (waiting()) gameInput(catchIsHit(catchStarX(ui.game, now())) ? 1 : 0);
        })];
      }
      return el("div", { class: "stack" }, row(...buttons), row(wideButton("Quit game", accent, shell, quitGame)));
    }
    case "STATUS":
      return row(wideButton("Next", accent, shell, nextStatusPage), wideButton("Done", accent, shell, closeMenu));
    default: {
      const paused = game.save.paused;
      const grid = el("div", { class: "grid" });
      for (const [action, icon, label] of ACTIONS) {
        const isPause = action === "PAUSE";
        const enabled = !paused || isPause;
        const rows = isPause && paused ? ACTION_ICONS.resume : ACTION_ICONS[icon];
        grid.append(el("button", {
          class: "action",
          disabled: !enabled,
          "aria-label": isPause && paused ? "Resume" : label,
          onclick: () => onAction(action),
        }, iconCanvas(rows, enabled ? accent : withAlpha(accent, 0.4), 26), el("span", {}, isPause && paused ? "Resume" : label)));
      }
      return grid;
    }
  }
}

// ---- settings dialog

function openSettings() {
  const dlg = $("#settings");
  const s = game.save.settings;
  dlg.querySelectorAll("[data-speed]").forEach((b) => b.setAttribute("aria-pressed", String(Number(b.dataset.speed) === s.speed)));
  $("#pause-toggle").checked = game.save.paused;
  $("#restart").hidden = !game.pet;
  dlg.showModal();
}

function wireSettings() {
  const dlg = $("#settings");
  dlg.querySelectorAll("[data-speed]").forEach((b) => b.addEventListener("click", () => {
    game.setSpeed(Number(b.dataset.speed), now());
    persist();
    openSettings();
    render();
  }));
  $("#pause-toggle").addEventListener("change", () => {
    togglePause();
    openSettings();
  });
  $("#skip-hour").addEventListener("click", () => {
    game.skipMinutes(60);
    checkEvolution();
    persist();
    render();
    say("Skipped ahead one hour.");
  });
  $("#restart").addEventListener("click", () => {
    if (confirm("Start over? You'll pick a new edition and hatch a new egg. Your collection is kept.")) {
      dlg.close();
      show("START");
    }
  });
  $("#settings-done").addEventListener("click", () => dlg.close());
  dlg.addEventListener("click", (e) => {
    if (e.target === dlg) dlg.close();
  });
}

// ------------------------------------------------------------------ install + badge

let installEvent = null;
window.addEventListener("beforeinstallprompt", (e) => {
  e.preventDefault();
  installEvent = e;
  render();
});
window.addEventListener("appinstalled", () => {
  installEvent = null;
  render();
});

async function promptInstall() {
  if (!installEvent) return;
  installEvent.prompt();
  await installEvent.userChoice.catch(() => null);
  installEvent = null;
  render();
}

function updateBadge() {
  const pet = game.pet;
  const calling = !!(pet && isAlive(pet) && pet.call);
  document.title = calling ? "(!) MyPet" : "MyPet";
  try {
    if (calling && navigator.setAppBadge) navigator.setAppBadge();
    else if (navigator.clearAppBadge) navigator.clearAppBadge();
  } catch {
    /* badges are optional */
  }
}

// ------------------------------------------------------------------ main loop

function frameLoop() {
  if (ui.screen === "DEVICE" && game.pet && lcdCanvas) {
    const t = now();
    const ratio = Math.min(window.devicePixelRatio || 1, 3);
    const w = Math.round(lcdCanvas.clientWidth * ratio);
    const h = Math.round(lcdCanvas.clientHeight * ratio);
    if (w && h) {
      if (lcdCanvas.width !== w || lcdCanvas.height !== h) {
        lcdCanvas.width = w;
        lcdCanvas.height = h;
      }
      const e = edition(game.pet.editionId);
      drawScene(lcdBuffer, ui, Math.floor(t / 500), t);
      paintLcd(lcdCanvas.getContext("2d"), lcdBuffer, w, h, e.lcdColor, e.inkColor);
    }
    // Keep the caption fresh (messages expire after a few seconds).
    const cap = $(".caption");
    if (cap) {
      const text = captionText();
      if (cap.textContent !== text) cap.textContent = text;
    }
  }
  requestAnimationFrame(frameLoop);
}

function onForeground() {
  // Another tab may have played with the pet while this one was hidden; keep the newer save.
  const fresh = loadSave();
  if (fresh && fresh.lastRealMillis > game.save.lastRealMillis) {
    game.save.pet = fresh.pet;
    game.save.lastRealMillis = fresh.lastRealMillis;
    game.save.carryMillis = fresh.carryMillis;
    game.save.paused = fresh.paused;
    game.save.settings = fresh.settings;
    game.save.history = fresh.history;
    game.save.discovered = fresh.discovered;
    lastEvolvedSeen = game.pet ? game.pet.evolvedAtMinute : -1;
  }
  tick();
  render();
}

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") {
    game.advanceTo(now());
    persist();
  } else {
    onForeground();
  }
});
window.addEventListener("pagehide", () => {
  game.advanceTo(now());
  persist();
});

// Debug hook for automated tests and curious keepers.
window.mypet = { game, ui, render, persist };

wireSettings();
game.advanceTo(now());
checkEvolution();
ui.anim = null; // Don't replay a flash for growth that happened while the app was closed.
persist();
render();
setInterval(tick, 1000);
requestAnimationFrame(frameLoop);

if ("serviceWorker" in navigator && location.protocol !== "file:") {
  navigator.serviceWorker.register("./sw.js").catch((err) => console.warn("Service worker not registered", err));
}
