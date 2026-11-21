// Saves the game in localStorage, which survives restarts and works fully offline.
import { normalizeSave } from "./game.js";

const KEY = "mypet.save.v1";

export function loadSave() {
  try {
    const text = localStorage.getItem(KEY);
    return normalizeSave(text ? JSON.parse(text) : null);
  } catch (err) {
    console.warn("Could not read the save; starting fresh.", err);
    return normalizeSave(null);
  }
}

export function storeSave(save) {
  try {
    localStorage.setItem(KEY, JSON.stringify(save));
  } catch (err) {
    console.warn("Could not write the save.", err);
  }
}

// Ask the browser not to evict our data when the device is low on space (best effort).
try {
  if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
} catch {
  /* not supported */
}
