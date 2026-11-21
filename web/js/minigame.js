// Five-round mini-games; win three rounds to cheer the pet up. Port of MiniGame.kt.
import { Rng } from "./game.js";

export const ROUNDS = 5;
export const WINS_NEEDED = 3;

export class MiniGame {
  constructor(kind, seed) {
    this.kind = kind;
    this.rng = new Rng(seed);
    this.round = 0;
    this.wins = 0;
    this.lastCorrect = null;
    // Which Way?
    this.petTurnedLeft = null;
    // Hi or Lo
    this.shown = this.rng.nextInt(1, 10);
    this.revealed = null;
    // Star Catch
    this.speed = 1 + this.rng.next() * 0.6;
  }

  get finished() {
    return this.round >= ROUNDS;
  }

  get won() {
    return this.wins >= WINS_NEEDED;
  }

  record(correct) {
    if (this.finished) throw new Error("Game is over");
    if (correct) this.wins++;
    this.round++;
    this.lastCorrect = correct;
    return correct;
  }

  /** Which Way?: guess which way the pet will turn. */
  guessWay(left) {
    const turn = this.rng.nextBool();
    this.petTurnedLeft = turn;
    return this.record(turn === left);
  }

  /** Hi or Lo: will the next digit be higher than the shown one? */
  guessHigher(higher) {
    let next;
    do next = this.rng.nextInt(1, 10);
    while (next === this.shown);
    this.revealed = next;
    const correct = next > this.shown === higher;
    this.shown = next;
    return this.record(correct);
  }

  /** Star Catch: the page animates the star and reports whether the tap landed. */
  submitCatch(hit) {
    const r = this.record(hit);
    this.speed = 1 + this.rng.next() * 0.6 + this.round * 0.15;
    return r;
  }
}
