/**
 * Recovered scene-only renderer from the user-approved V10.1 offline prototype.
 * Generated dependency closure: no Vue application, business fixtures, request client or UI shell.
 * Building geometry and animated scene objects are authored model content, not telemetry.
 * Keep the supplied model identity separate from formally authorized building/equipment IDs.
 * Third-party notices: ./NOTICE.txt.
 */
import { preparedModel, preparedLandscape, roadDistance } from "./scene-preparation";

const nN = [{ id: "elevator", name: "\u7535\u68AF", description: "\u4E00\u90E8\u5178\u578B\u7535\u68AF \xB7 \u5347\u964D\u6F14\u793A", color: "#247eb1" }, { id: "hvac", name: "\u7A7A\u8C03", description: "\u4E00\u53F0\u5916\u673A + \u4E00\u53F0\u5185\u673A\u793A\u610F", color: "#008577" }, { id: "lighting", name: "\u7167\u660E", description: "\u4E00\u76CF\u5178\u578B\u7167\u660E\u706F\u5177", color: "#a36a0b" }, { id: "fire", name: "\u6D88\u9632", description: "\u4E00\u6BB5\u5178\u578B\u6D88\u9632\u914D\u7535\u6865\u67B6", color: "#b34e39" }];
function oI(t, e, f) {
  const n = [...f.map((l, W) => W), ...f.slice(1, -1).map((l, W) => f.length - 2 - W)], v = t + e * 3.2, r = Math.floor(v / 10) % n.length, z = v % 10, u = n[r], b = n[(r + 1) % n.length], a = z >= 4, P = a ? Math.min(1, (z - 4) / 6) : 0, s = P * P * (3 - 2 * P), d = f[u].y + (f[b].y - f[u].y) * s, c = z < 1 ? z : z < 2.8 ? 1 : z < 4 ? (4 - z) / 1.2 : 0;
  return { y: d, open: c, from: f[u].name, to: f[b].name, position: a ? `${f[u].name} \u2192 ${f[b].name}` : f[u].name, direction: a ? b > u ? "\u4E0A\u884C" : "\u4E0B\u884C" : "\u505C\u9760", door: a ? "\u5173\u95ED" : z < 1 ? "\u5F00\u95E8\u4E2D" : z < 2.8 ? "\u5DF2\u5F00\u542F" : "\u5173\u95E8\u4E2D", travel: a };
}
const wI = [{ id: "EL-01", label: "\u7535\u68AF\u6F14\u793A 01", system: "elevator", floor: "\u591A\u5C42", center: [-18.081119537353516, 0, 0.905482180416584], floors: [{ name: "F1", y: 0 }, { name: "F2", y: 4.2 }, { name: "F3", y: 8 }, { name: "F4", y: 11.8 }, { name: "F5", y: 15.6 }, { name: "F6", y: 19.4 }], sources: ["F1_D90_Sill", "F2_D36_Sill", "F3_D51_Sill", "F4_D81_Sill", "F5_D23_Sill", "F6_D9_Sill"], maxPlanDeviationM: 0 }, { id: "EL-02", label: "\u7535\u68AF\u6F14\u793A 02", system: "elevator", floor: "\u591A\u5C42", center: [-16.806833267211914, 0, 3.171799898147583], floors: [{ name: "F1", y: 0 }, { name: "F2", y: 4.2 }, { name: "F3", y: 8 }, { name: "F4", y: 11.8 }, { name: "F5", y: 15.6 }, { name: "F6", y: 19.4 }], sources: ["F1_D295_Sill", "F2_D177_Sill", "F3_D273_Sill", "F4_D280_Sill", "F5_D129_Sill", "F6_D146_Sill"], maxPlanDeviationM: 0 }, { id: "EL-03", label: "\u7535\u68AF\u6F14\u793A 03", system: "elevator", floor: "\u591A\u5C42", center: [1.2697487771511078, 0, -9.974955558776855], floors: [{ name: "F1", y: 0 }, { name: "F2", y: 4.2 }, { name: "F3", y: 8 }, { name: "F4", y: 11.8 }, { name: "F5", y: 15.6 }, { name: "F6", y: 19.4 }], sources: ["F1_D21_Sill", "F2_D21_Sill", "F3_D32_Sill", "F4_D272_Sill", "F5_D127_Sill", "F6_D144_Sill"], maxPlanDeviationM: 42111936460749504e-20 }, { id: "EL-04", label: "\u7535\u68AF\u6F14\u793A 04", system: "elevator", floor: "\u591A\u5C42", center: [2.544034242630005, 0, -7.708637952804565], floors: [{ name: "F1", y: 0 }, { name: "F2", y: 4.2 }, { name: "F3", y: 8 }, { name: "F4", y: 11.8 }, { name: "F5", y: 15.6 }, { name: "F6", y: 19.4 }], sources: ["F1_D8_Sill", "F2_D20_Sill", "F3_D33_Sill", "F4_D273_Sill", "F5_D128_Sill", "F6_D145_Sill"], maxPlanDeviationM: 42049589492397694e-20 }, { id: "EL-05", label: "\u7535\u68AF\u6F14\u793A 05", system: "elevator", floor: "\u591A\u5C42", center: [56.28706359863281, 0, 19.17615509033203], floors: [{ name: "F1", y: 0 }, { name: "F2", y: 4.2 }, { name: "F3", y: 8 }, { name: "F4", y: 11.8 }], sources: ["F1_D249_Sill", "F2_D249_Sill", "F3_D193_Sill", "F4_D210_Sill"], maxPlanDeviationM: 0.10000038146972656 }];
const XDt = [{ id: "F1_Slab", parts: [{ material: "M_Stone_floor", start: 350079, count: 8580 }] }, { id: "F2_Slab", parts: [{ material: "M_Stone_floor", start: 358875, count: 8040 }] }, { id: "F3_Slab", parts: [{ material: "M_Stone_floor", start: 367635, count: 8448 }] }, { id: "F4_Slab", parts: [{ material: "M_Stone_floor", start: 376227, count: 9480 }] }, { id: "F5_Slab", parts: [{ material: "M_Stone_floor", start: 385995, count: 6924 }] }, { id: "F6_Slab", parts: [{ material: "M_Stone_floor", start: 393171, count: 4512 }] }];
const vN = "180";
const Fa = { ROTATE: 0, DOLLY: 1, PAN: 2 };
const Ra = { ROTATE: 0, PAN: 1, DOLLY_PAN: 2, DOLLY_ROTATE: 3 };
const IDt = 0;
const hI = 1;
const KDt = 2;
const GI = 1;
const VI = 2;
const s9 = 3;
const M5 = 0;
const Y3 = 1;
const $3 = 2;
const i5 = 0;
const ka = 1;
const YI = 2;
const DI = 3;
const qI = 4;
const EDt = 5;
const xb = 100;
const CDt = 101;
const ADt = 102;
const QDt = 103;
const _Dt = 104;
const $Dt = 200;
const tqt = 201;
const eqt = 202;
const fqt = 203;
const rN = 204;
const zN = 205;
const nqt = 206;
const vqt = 207;
const rqt = 208;
const zqt = 209;
const uqt = 210;
const bqt = 211;
const aqt = 212;
const Pqt = 213;
const sqt = 214;
const uN = 0;
const bN = 1;
const aN = 2;
const Ja = 3;
const PN = 4;
const sN = 5;
const dN = 6;
const cN = 7;
const FI = 0;
const dqt = 1;
const cqt = 2;
const p5 = 0;
const lqt = 1;
const Xqt = 2;
const Wqt = 3;
const RI = 4;
const yqt = 5;
const Zqt = 6;
const mqt = 7;
const kI = 300;
const ga = 301;
const Sa = 302;
const $W = 303;
const lN = 304;
const t6 = 306;
const e6 = 1e3;
const Lb = 1001;
const XN = 1002;
const tr = 1003;
const jqt = 1004;
const f6 = 1005;
const Wz = 1006;
const WN = 1007;
const Ob = 1008;
const yz = 1009;
const JI = 1010;
const gI = 1011;
const Hs = 1012;
const yN = 1013;
const Hb = 1014;
const Zz = 1015;
const Ba = 1016;
const ZN = 1017;
const mN = 1018;
const Ns = 1020;
const SI = 35902;
const BI = 35899;
const UI = 1021;
const II = 1022;
const W7 = 1023;
const Ms = 1026;
const is = 1027;
const jN = 1028;
const xN = 1029;
const KI = 1030;
const LN = 1031;
const ON = 1033;
const n6 = 33776;
const v6 = 33777;
const r6 = 33778;
const z6 = 33779;
const HN = 35840;
const NN = 35841;
const MN = 35842;
const iN = 35843;
const pN = 36196;
const TN = 37492;
const oN = 37496;
const wN = 37808;
const hN = 37809;
const GN = 37810;
const VN = 37811;
const YN = 37812;
const DN = 37813;
const qN = 37814;
const FN = 37815;
const RN = 37816;
const kN = 37817;
const JN = 37818;
const gN = 37819;
const SN = 37820;
const BN = 37821;
const UN = 36492;
const IN = 36494;
const KN = 36495;
const EN = 36283;
const CN = 36284;
const AN = 36285;
const QN = 36286;
const xqt = 3200;
const Lqt = 3201;
const EI = 0;
const Oqt = 1;
const T5 = "";
const Qv = "srgb";
const Ua = "srgb-linear";
const u6 = "linear";
const dn = "srgb";
const Ia = 7680;
const CI = 519;
const Hqt = 512;
const Nqt = 513;
const Mqt = 514;
const AI = 515;
const iqt = 516;
const pqt = 517;
const Tqt = 518;
const oqt = 519;
const _N = 35044;
const QI = "300 es";
const mz = 2e3;
const b6 = 2001;
class Nb {
  addEventListener(e, f) {
    this._listeners === void 0 && (this._listeners = {});
    const n = this._listeners;
    n[e] === void 0 && (n[e] = []), n[e].indexOf(f) === -1 && n[e].push(f);
  }
  hasEventListener(e, f) {
    const n = this._listeners;
    return n === void 0 ? false : n[e] !== void 0 && n[e].indexOf(f) !== -1;
  }
  removeEventListener(e, f) {
    const n = this._listeners;
    if (n === void 0) return;
    const v = n[e];
    if (v !== void 0) {
      const r = v.indexOf(f);
      r !== -1 && v.splice(r, 1);
    }
  }
  dispatchEvent(e) {
    const f = this._listeners;
    if (f === void 0) return;
    const n = f[e.type];
    if (n !== void 0) {
      e.target = this;
      const v = n.slice(0);
      for (let r = 0, z = v.length; r < z; r++) v[r].call(this, e);
      e.target = null;
    }
  }
}
const _v = ["00", "01", "02", "03", "04", "05", "06", "07", "08", "09", "0a", "0b", "0c", "0d", "0e", "0f", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "1a", "1b", "1c", "1d", "1e", "1f", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "2a", "2b", "2c", "2d", "2e", "2f", "30", "31", "32", "33", "34", "35", "36", "37", "38", "39", "3a", "3b", "3c", "3d", "3e", "3f", "40", "41", "42", "43", "44", "45", "46", "47", "48", "49", "4a", "4b", "4c", "4d", "4e", "4f", "50", "51", "52", "53", "54", "55", "56", "57", "58", "59", "5a", "5b", "5c", "5d", "5e", "5f", "60", "61", "62", "63", "64", "65", "66", "67", "68", "69", "6a", "6b", "6c", "6d", "6e", "6f", "70", "71", "72", "73", "74", "75", "76", "77", "78", "79", "7a", "7b", "7c", "7d", "7e", "7f", "80", "81", "82", "83", "84", "85", "86", "87", "88", "89", "8a", "8b", "8c", "8d", "8e", "8f", "90", "91", "92", "93", "94", "95", "96", "97", "98", "99", "9a", "9b", "9c", "9d", "9e", "9f", "a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "aa", "ab", "ac", "ad", "ae", "af", "b0", "b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8", "b9", "ba", "bb", "bc", "bd", "be", "bf", "c0", "c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "ca", "cb", "cc", "cd", "ce", "cf", "d0", "d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "d9", "da", "db", "dc", "dd", "de", "df", "e0", "e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "e9", "ea", "eb", "ec", "ed", "ee", "ef", "f0", "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "fa", "fb", "fc", "fd", "fe", "ff"];
let _I = 1234567;
const Ka = Math.PI / 180;
const ps = 180 / Math.PI;
function jz() {
  const t = Math.random() * 4294967295 | 0, e = Math.random() * 4294967295 | 0, f = Math.random() * 4294967295 | 0, n = Math.random() * 4294967295 | 0;
  return (_v[t & 255] + _v[t >> 8 & 255] + _v[t >> 16 & 255] + _v[t >> 24 & 255] + "-" + _v[e & 255] + _v[e >> 8 & 255] + "-" + _v[e >> 16 & 15 | 64] + _v[e >> 24 & 255] + "-" + _v[f & 63 | 128] + _v[f >> 8 & 255] + "-" + _v[f >> 16 & 255] + _v[f >> 24 & 255] + _v[n & 255] + _v[n >> 8 & 255] + _v[n >> 16 & 255] + _v[n >> 24 & 255]).toLowerCase();
}
function pf(t, e, f) {
  return Math.max(e, Math.min(f, t));
}
function $N(t, e) {
  return (t % e + e) % e;
}
function wqt(t, e, f, n, v) {
  return n + (t - e) * (v - n) / (f - e);
}
function hqt(t, e, f) {
  return t !== e ? (f - t) / (e - t) : 0;
}
function Ts(t, e, f) {
  return (1 - f) * t + f * e;
}
function Gqt(t, e, f, n) {
  return Ts(t, e, 1 - Math.exp(-f * n));
}
function Vqt(t, e = 1) {
  return e - Math.abs($N(t, e * 2) - e);
}
function Yqt(t, e, f) {
  return t <= e ? 0 : t >= f ? 1 : (t = (t - e) / (f - e), t * t * (3 - 2 * t));
}
function Dqt(t, e, f) {
  return t <= e ? 0 : t >= f ? 1 : (t = (t - e) / (f - e), t * t * t * (t * (t * 6 - 15) + 10));
}
function qqt(t, e) {
  return t + Math.floor(Math.random() * (e - t + 1));
}
function Fqt(t, e) {
  return t + Math.random() * (e - t);
}
function Rqt(t) {
  return t * (0.5 - Math.random());
}
function kqt(t) {
  t !== void 0 && (_I = t);
  let e = _I += 1831565813;
  return e = Math.imul(e ^ e >>> 15, e | 1), e ^= e + Math.imul(e ^ e >>> 7, e | 61), ((e ^ e >>> 14) >>> 0) / 4294967296;
}
function Jqt(t) {
  return t * Ka;
}
function gqt(t) {
  return t * ps;
}
function Sqt(t) {
  return (t & t - 1) === 0 && t !== 0;
}
function Bqt(t) {
  return Math.pow(2, Math.ceil(Math.log(t) / Math.LN2));
}
function Uqt(t) {
  return Math.pow(2, Math.floor(Math.log(t) / Math.LN2));
}
function Iqt(t, e, f, n, v) {
  const r = Math.cos, z = Math.sin, u = r(f / 2), b = z(f / 2), a = r((e + n) / 2), P = z((e + n) / 2), s = r((e - n) / 2), d = z((e - n) / 2), c = r((n - e) / 2), l = z((n - e) / 2);
  switch (v) {
    case "XYX":
      t.set(u * P, b * s, b * d, u * a);
      break;
    case "YZY":
      t.set(b * d, u * P, b * s, u * a);
      break;
    case "ZXZ":
      t.set(b * s, b * d, u * P, u * a);
      break;
    case "XZX":
      t.set(u * P, b * l, b * c, u * a);
      break;
    case "YXY":
      t.set(b * c, u * P, b * l, u * a);
      break;
    case "ZYZ":
      t.set(b * l, b * c, u * P, u * a);
      break;
    default:
      console.warn("THREE.MathUtils: .setQuaternionFromProperEuler() encountered an unknown order: " + v);
  }
}
function y7(t, e) {
  switch (e.constructor) {
    case Float32Array:
      return t;
    case Uint32Array:
      return t / 4294967295;
    case Uint16Array:
      return t / 65535;
    case Uint8Array:
      return t / 255;
    case Int32Array:
      return Math.max(t / 2147483647, -1);
    case Int16Array:
      return Math.max(t / 32767, -1);
    case Int8Array:
      return Math.max(t / 127, -1);
    default:
      throw new Error("Invalid component type.");
  }
}
function un(t, e) {
  switch (e.constructor) {
    case Float32Array:
      return t;
    case Uint32Array:
      return Math.round(t * 4294967295);
    case Uint16Array:
      return Math.round(t * 65535);
    case Uint8Array:
      return Math.round(t * 255);
    case Int32Array:
      return Math.round(t * 2147483647);
    case Int16Array:
      return Math.round(t * 32767);
    case Int8Array:
      return Math.round(t * 127);
    default:
      throw new Error("Invalid component type.");
  }
}
const a6 = { DEG2RAD: Ka, RAD2DEG: ps, generateUUID: jz, clamp: pf, euclideanModulo: $N, mapLinear: wqt, inverseLerp: hqt, lerp: Ts, damp: Gqt, pingpong: Vqt, smoothstep: Yqt, smootherstep: Dqt, randInt: qqt, randFloat: Fqt, randFloatSpread: Rqt, seededRandom: kqt, degToRad: Jqt, radToDeg: gqt, isPowerOfTwo: Sqt, ceilPowerOfTwo: Bqt, floorPowerOfTwo: Uqt, setQuaternionFromProperEuler: Iqt, normalize: un, denormalize: y7 };
class Me {
  constructor(e = 0, f = 0) {
    Me.prototype.isVector2 = true, this.x = e, this.y = f;
  }
  get width() {
    return this.x;
  }
  set width(e) {
    this.x = e;
  }
  get height() {
    return this.y;
  }
  set height(e) {
    this.y = e;
  }
  set(e, f) {
    return this.x = e, this.y = f, this;
  }
  setScalar(e) {
    return this.x = e, this.y = e, this;
  }
  setX(e) {
    return this.x = e, this;
  }
  setY(e) {
    return this.y = e, this;
  }
  setComponent(e, f) {
    switch (e) {
      case 0:
        this.x = f;
        break;
      case 1:
        this.y = f;
        break;
      default:
        throw new Error("index is out of range: " + e);
    }
    return this;
  }
  getComponent(e) {
    switch (e) {
      case 0:
        return this.x;
      case 1:
        return this.y;
      default:
        throw new Error("index is out of range: " + e);
    }
  }
  clone() {
    return new this.constructor(this.x, this.y);
  }
  copy(e) {
    return this.x = e.x, this.y = e.y, this;
  }
  add(e) {
    return this.x += e.x, this.y += e.y, this;
  }
  addScalar(e) {
    return this.x += e, this.y += e, this;
  }
  addVectors(e, f) {
    return this.x = e.x + f.x, this.y = e.y + f.y, this;
  }
  addScaledVector(e, f) {
    return this.x += e.x * f, this.y += e.y * f, this;
  }
  sub(e) {
    return this.x -= e.x, this.y -= e.y, this;
  }
  subScalar(e) {
    return this.x -= e, this.y -= e, this;
  }
  subVectors(e, f) {
    return this.x = e.x - f.x, this.y = e.y - f.y, this;
  }
  multiply(e) {
    return this.x *= e.x, this.y *= e.y, this;
  }
  multiplyScalar(e) {
    return this.x *= e, this.y *= e, this;
  }
  divide(e) {
    return this.x /= e.x, this.y /= e.y, this;
  }
  divideScalar(e) {
    return this.multiplyScalar(1 / e);
  }
  applyMatrix3(e) {
    const f = this.x, n = this.y, v = e.elements;
    return this.x = v[0] * f + v[3] * n + v[6], this.y = v[1] * f + v[4] * n + v[7], this;
  }
  min(e) {
    return this.x = Math.min(this.x, e.x), this.y = Math.min(this.y, e.y), this;
  }
  max(e) {
    return this.x = Math.max(this.x, e.x), this.y = Math.max(this.y, e.y), this;
  }
  clamp(e, f) {
    return this.x = pf(this.x, e.x, f.x), this.y = pf(this.y, e.y, f.y), this;
  }
  clampScalar(e, f) {
    return this.x = pf(this.x, e, f), this.y = pf(this.y, e, f), this;
  }
  clampLength(e, f) {
    const n = this.length();
    return this.divideScalar(n || 1).multiplyScalar(pf(n, e, f));
  }
  floor() {
    return this.x = Math.floor(this.x), this.y = Math.floor(this.y), this;
  }
  ceil() {
    return this.x = Math.ceil(this.x), this.y = Math.ceil(this.y), this;
  }
  round() {
    return this.x = Math.round(this.x), this.y = Math.round(this.y), this;
  }
  roundToZero() {
    return this.x = Math.trunc(this.x), this.y = Math.trunc(this.y), this;
  }
  negate() {
    return this.x = -this.x, this.y = -this.y, this;
  }
  dot(e) {
    return this.x * e.x + this.y * e.y;
  }
  cross(e) {
    return this.x * e.y - this.y * e.x;
  }
  lengthSq() {
    return this.x * this.x + this.y * this.y;
  }
  length() {
    return Math.sqrt(this.x * this.x + this.y * this.y);
  }
  manhattanLength() {
    return Math.abs(this.x) + Math.abs(this.y);
  }
  normalize() {
    return this.divideScalar(this.length() || 1);
  }
  angle() {
    return Math.atan2(-this.y, -this.x) + Math.PI;
  }
  angleTo(e) {
    const f = Math.sqrt(this.lengthSq() * e.lengthSq());
    if (f === 0) return Math.PI / 2;
    const n = this.dot(e) / f;
    return Math.acos(pf(n, -1, 1));
  }
  distanceTo(e) {
    return Math.sqrt(this.distanceToSquared(e));
  }
  distanceToSquared(e) {
    const f = this.x - e.x, n = this.y - e.y;
    return f * f + n * n;
  }
  manhattanDistanceTo(e) {
    return Math.abs(this.x - e.x) + Math.abs(this.y - e.y);
  }
  setLength(e) {
    return this.normalize().multiplyScalar(e);
  }
  lerp(e, f) {
    return this.x += (e.x - this.x) * f, this.y += (e.y - this.y) * f, this;
  }
  lerpVectors(e, f, n) {
    return this.x = e.x + (f.x - e.x) * n, this.y = e.y + (f.y - e.y) * n, this;
  }
  equals(e) {
    return e.x === this.x && e.y === this.y;
  }
  fromArray(e, f = 0) {
    return this.x = e[f], this.y = e[f + 1], this;
  }
  toArray(e = [], f = 0) {
    return e[f] = this.x, e[f + 1] = this.y, e;
  }
  fromBufferAttribute(e, f) {
    return this.x = e.getX(f), this.y = e.getY(f), this;
  }
  rotateAround(e, f) {
    const n = Math.cos(f), v = Math.sin(f), r = this.x - e.x, z = this.y - e.y;
    return this.x = r * n - z * v + e.x, this.y = r * v + z * n + e.y, this;
  }
  random() {
    return this.x = Math.random(), this.y = Math.random(), this;
  }
  *[Symbol.iterator]() {
    yield this.x, yield this.y;
  }
}
class Mb {
  constructor(e = 0, f = 0, n = 0, v = 1) {
    this.isQuaternion = true, this._x = e, this._y = f, this._z = n, this._w = v;
  }
  static slerpFlat(e, f, n, v, r, z, u) {
    let b = n[v + 0], a = n[v + 1], P = n[v + 2], s = n[v + 3];
    const d = r[z + 0], c = r[z + 1], l = r[z + 2], W = r[z + 3];
    if (u === 0) {
      e[f + 0] = b, e[f + 1] = a, e[f + 2] = P, e[f + 3] = s;
      return;
    }
    if (u === 1) {
      e[f + 0] = d, e[f + 1] = c, e[f + 2] = l, e[f + 3] = W;
      return;
    }
    if (s !== W || b !== d || a !== c || P !== l) {
      let X = 1 - u;
      const y = b * d + a * c + P * l + s * W, m = y >= 0 ? 1 : -1, j = 1 - y * y;
      if (j > Number.EPSILON) {
        const L = Math.sqrt(j), O = Math.atan2(L, y * m);
        X = Math.sin(X * O) / L, u = Math.sin(u * O) / L;
      }
      const x = u * m;
      if (b = b * X + d * x, a = a * X + c * x, P = P * X + l * x, s = s * X + W * x, X === 1 - u) {
        const L = 1 / Math.sqrt(b * b + a * a + P * P + s * s);
        b *= L, a *= L, P *= L, s *= L;
      }
    }
    e[f] = b, e[f + 1] = a, e[f + 2] = P, e[f + 3] = s;
  }
  static multiplyQuaternionsFlat(e, f, n, v, r, z) {
    const u = n[v], b = n[v + 1], a = n[v + 2], P = n[v + 3], s = r[z], d = r[z + 1], c = r[z + 2], l = r[z + 3];
    return e[f] = u * l + P * s + b * c - a * d, e[f + 1] = b * l + P * d + a * s - u * c, e[f + 2] = a * l + P * c + u * d - b * s, e[f + 3] = P * l - u * s - b * d - a * c, e;
  }
  get x() {
    return this._x;
  }
  set x(e) {
    this._x = e, this._onChangeCallback();
  }
  get y() {
    return this._y;
  }
  set y(e) {
    this._y = e, this._onChangeCallback();
  }
  get z() {
    return this._z;
  }
  set z(e) {
    this._z = e, this._onChangeCallback();
  }
  get w() {
    return this._w;
  }
  set w(e) {
    this._w = e, this._onChangeCallback();
  }
  set(e, f, n, v) {
    return this._x = e, this._y = f, this._z = n, this._w = v, this._onChangeCallback(), this;
  }
  clone() {
    return new this.constructor(this._x, this._y, this._z, this._w);
  }
  copy(e) {
    return this._x = e.x, this._y = e.y, this._z = e.z, this._w = e.w, this._onChangeCallback(), this;
  }
  setFromEuler(e, f = true) {
    const n = e._x, v = e._y, r = e._z, z = e._order, u = Math.cos, b = Math.sin, a = u(n / 2), P = u(v / 2), s = u(r / 2), d = b(n / 2), c = b(v / 2), l = b(r / 2);
    switch (z) {
      case "XYZ":
        this._x = d * P * s + a * c * l, this._y = a * c * s - d * P * l, this._z = a * P * l + d * c * s, this._w = a * P * s - d * c * l;
        break;
      case "YXZ":
        this._x = d * P * s + a * c * l, this._y = a * c * s - d * P * l, this._z = a * P * l - d * c * s, this._w = a * P * s + d * c * l;
        break;
      case "ZXY":
        this._x = d * P * s - a * c * l, this._y = a * c * s + d * P * l, this._z = a * P * l + d * c * s, this._w = a * P * s - d * c * l;
        break;
      case "ZYX":
        this._x = d * P * s - a * c * l, this._y = a * c * s + d * P * l, this._z = a * P * l - d * c * s, this._w = a * P * s + d * c * l;
        break;
      case "YZX":
        this._x = d * P * s + a * c * l, this._y = a * c * s + d * P * l, this._z = a * P * l - d * c * s, this._w = a * P * s - d * c * l;
        break;
      case "XZY":
        this._x = d * P * s - a * c * l, this._y = a * c * s - d * P * l, this._z = a * P * l + d * c * s, this._w = a * P * s + d * c * l;
        break;
      default:
        console.warn("THREE.Quaternion: .setFromEuler() encountered an unknown order: " + z);
    }
    return f === true && this._onChangeCallback(), this;
  }
  setFromAxisAngle(e, f) {
    const n = f / 2, v = Math.sin(n);
    return this._x = e.x * v, this._y = e.y * v, this._z = e.z * v, this._w = Math.cos(n), this._onChangeCallback(), this;
  }
  setFromRotationMatrix(e) {
    const f = e.elements, n = f[0], v = f[4], r = f[8], z = f[1], u = f[5], b = f[9], a = f[2], P = f[6], s = f[10], d = n + u + s;
    if (d > 0) {
      const c = 0.5 / Math.sqrt(d + 1);
      this._w = 0.25 / c, this._x = (P - b) * c, this._y = (r - a) * c, this._z = (z - v) * c;
    } else if (n > u && n > s) {
      const c = 2 * Math.sqrt(1 + n - u - s);
      this._w = (P - b) / c, this._x = 0.25 * c, this._y = (v + z) / c, this._z = (r + a) / c;
    } else if (u > s) {
      const c = 2 * Math.sqrt(1 + u - n - s);
      this._w = (r - a) / c, this._x = (v + z) / c, this._y = 0.25 * c, this._z = (b + P) / c;
    } else {
      const c = 2 * Math.sqrt(1 + s - n - u);
      this._w = (z - v) / c, this._x = (r + a) / c, this._y = (b + P) / c, this._z = 0.25 * c;
    }
    return this._onChangeCallback(), this;
  }
  setFromUnitVectors(e, f) {
    let n = e.dot(f) + 1;
    return n < 1e-8 ? (n = 0, Math.abs(e.x) > Math.abs(e.z) ? (this._x = -e.y, this._y = e.x, this._z = 0, this._w = n) : (this._x = 0, this._y = -e.z, this._z = e.y, this._w = n)) : (this._x = e.y * f.z - e.z * f.y, this._y = e.z * f.x - e.x * f.z, this._z = e.x * f.y - e.y * f.x, this._w = n), this.normalize();
  }
  angleTo(e) {
    return 2 * Math.acos(Math.abs(pf(this.dot(e), -1, 1)));
  }
  rotateTowards(e, f) {
    const n = this.angleTo(e);
    if (n === 0) return this;
    const v = Math.min(1, f / n);
    return this.slerp(e, v), this;
  }
  identity() {
    return this.set(0, 0, 0, 1);
  }
  invert() {
    return this.conjugate();
  }
  conjugate() {
    return this._x *= -1, this._y *= -1, this._z *= -1, this._onChangeCallback(), this;
  }
  dot(e) {
    return this._x * e._x + this._y * e._y + this._z * e._z + this._w * e._w;
  }
  lengthSq() {
    return this._x * this._x + this._y * this._y + this._z * this._z + this._w * this._w;
  }
  length() {
    return Math.sqrt(this._x * this._x + this._y * this._y + this._z * this._z + this._w * this._w);
  }
  normalize() {
    let e = this.length();
    return e === 0 ? (this._x = 0, this._y = 0, this._z = 0, this._w = 1) : (e = 1 / e, this._x = this._x * e, this._y = this._y * e, this._z = this._z * e, this._w = this._w * e), this._onChangeCallback(), this;
  }
  multiply(e) {
    return this.multiplyQuaternions(this, e);
  }
  premultiply(e) {
    return this.multiplyQuaternions(e, this);
  }
  multiplyQuaternions(e, f) {
    const n = e._x, v = e._y, r = e._z, z = e._w, u = f._x, b = f._y, a = f._z, P = f._w;
    return this._x = n * P + z * u + v * a - r * b, this._y = v * P + z * b + r * u - n * a, this._z = r * P + z * a + n * b - v * u, this._w = z * P - n * u - v * b - r * a, this._onChangeCallback(), this;
  }
  slerp(e, f) {
    if (f === 0) return this;
    if (f === 1) return this.copy(e);
    const n = this._x, v = this._y, r = this._z, z = this._w;
    let u = z * e._w + n * e._x + v * e._y + r * e._z;
    if (u < 0 ? (this._w = -e._w, this._x = -e._x, this._y = -e._y, this._z = -e._z, u = -u) : this.copy(e), u >= 1) return this._w = z, this._x = n, this._y = v, this._z = r, this;
    const b = 1 - u * u;
    if (b <= Number.EPSILON) {
      const c = 1 - f;
      return this._w = c * z + f * this._w, this._x = c * n + f * this._x, this._y = c * v + f * this._y, this._z = c * r + f * this._z, this.normalize(), this;
    }
    const a = Math.sqrt(b), P = Math.atan2(a, u), s = Math.sin((1 - f) * P) / a, d = Math.sin(f * P) / a;
    return this._w = z * s + this._w * d, this._x = n * s + this._x * d, this._y = v * s + this._y * d, this._z = r * s + this._z * d, this._onChangeCallback(), this;
  }
  slerpQuaternions(e, f, n) {
    return this.copy(e).slerp(f, n);
  }
  random() {
    const e = 2 * Math.PI * Math.random(), f = 2 * Math.PI * Math.random(), n = Math.random(), v = Math.sqrt(1 - n), r = Math.sqrt(n);
    return this.set(v * Math.sin(e), v * Math.cos(e), r * Math.sin(f), r * Math.cos(f));
  }
  equals(e) {
    return e._x === this._x && e._y === this._y && e._z === this._z && e._w === this._w;
  }
  fromArray(e, f = 0) {
    return this._x = e[f], this._y = e[f + 1], this._z = e[f + 2], this._w = e[f + 3], this._onChangeCallback(), this;
  }
  toArray(e = [], f = 0) {
    return e[f] = this._x, e[f + 1] = this._y, e[f + 2] = this._z, e[f + 3] = this._w, e;
  }
  fromBufferAttribute(e, f) {
    return this._x = e.getX(f), this._y = e.getY(f), this._z = e.getZ(f), this._w = e.getW(f), this._onChangeCallback(), this;
  }
  toJSON() {
    return this.toArray();
  }
  _onChange(e) {
    return this._onChangeCallback = e, this;
  }
  _onChangeCallback() {
  }
  *[Symbol.iterator]() {
    yield this._x, yield this._y, yield this._z, yield this._w;
  }
}
class ht {
  constructor(e = 0, f = 0, n = 0) {
    ht.prototype.isVector3 = true, this.x = e, this.y = f, this.z = n;
  }
  set(e, f, n) {
    return n === void 0 && (n = this.z), this.x = e, this.y = f, this.z = n, this;
  }
  setScalar(e) {
    return this.x = e, this.y = e, this.z = e, this;
  }
  setX(e) {
    return this.x = e, this;
  }
  setY(e) {
    return this.y = e, this;
  }
  setZ(e) {
    return this.z = e, this;
  }
  setComponent(e, f) {
    switch (e) {
      case 0:
        this.x = f;
        break;
      case 1:
        this.y = f;
        break;
      case 2:
        this.z = f;
        break;
      default:
        throw new Error("index is out of range: " + e);
    }
    return this;
  }
  getComponent(e) {
    switch (e) {
      case 0:
        return this.x;
      case 1:
        return this.y;
      case 2:
        return this.z;
      default:
        throw new Error("index is out of range: " + e);
    }
  }
  clone() {
    return new this.constructor(this.x, this.y, this.z);
  }
  copy(e) {
    return this.x = e.x, this.y = e.y, this.z = e.z, this;
  }
  add(e) {
    return this.x += e.x, this.y += e.y, this.z += e.z, this;
  }
  addScalar(e) {
    return this.x += e, this.y += e, this.z += e, this;
  }
  addVectors(e, f) {
    return this.x = e.x + f.x, this.y = e.y + f.y, this.z = e.z + f.z, this;
  }
  addScaledVector(e, f) {
    return this.x += e.x * f, this.y += e.y * f, this.z += e.z * f, this;
  }
  sub(e) {
    return this.x -= e.x, this.y -= e.y, this.z -= e.z, this;
  }
  subScalar(e) {
    return this.x -= e, this.y -= e, this.z -= e, this;
  }
  subVectors(e, f) {
    return this.x = e.x - f.x, this.y = e.y - f.y, this.z = e.z - f.z, this;
  }
  multiply(e) {
    return this.x *= e.x, this.y *= e.y, this.z *= e.z, this;
  }
  multiplyScalar(e) {
    return this.x *= e, this.y *= e, this.z *= e, this;
  }
  multiplyVectors(e, f) {
    return this.x = e.x * f.x, this.y = e.y * f.y, this.z = e.z * f.z, this;
  }
  applyEuler(e) {
    return this.applyQuaternion($I.setFromEuler(e));
  }
  applyAxisAngle(e, f) {
    return this.applyQuaternion($I.setFromAxisAngle(e, f));
  }
  applyMatrix3(e) {
    const f = this.x, n = this.y, v = this.z, r = e.elements;
    return this.x = r[0] * f + r[3] * n + r[6] * v, this.y = r[1] * f + r[4] * n + r[7] * v, this.z = r[2] * f + r[5] * n + r[8] * v, this;
  }
  applyNormalMatrix(e) {
    return this.applyMatrix3(e).normalize();
  }
  applyMatrix4(e) {
    const f = this.x, n = this.y, v = this.z, r = e.elements, z = 1 / (r[3] * f + r[7] * n + r[11] * v + r[15]);
    return this.x = (r[0] * f + r[4] * n + r[8] * v + r[12]) * z, this.y = (r[1] * f + r[5] * n + r[9] * v + r[13]) * z, this.z = (r[2] * f + r[6] * n + r[10] * v + r[14]) * z, this;
  }
  applyQuaternion(e) {
    const f = this.x, n = this.y, v = this.z, r = e.x, z = e.y, u = e.z, b = e.w, a = 2 * (z * v - u * n), P = 2 * (u * f - r * v), s = 2 * (r * n - z * f);
    return this.x = f + b * a + z * s - u * P, this.y = n + b * P + u * a - r * s, this.z = v + b * s + r * P - z * a, this;
  }
  project(e) {
    return this.applyMatrix4(e.matrixWorldInverse).applyMatrix4(e.projectionMatrix);
  }
  unproject(e) {
    return this.applyMatrix4(e.projectionMatrixInverse).applyMatrix4(e.matrixWorld);
  }
  transformDirection(e) {
    const f = this.x, n = this.y, v = this.z, r = e.elements;
    return this.x = r[0] * f + r[4] * n + r[8] * v, this.y = r[1] * f + r[5] * n + r[9] * v, this.z = r[2] * f + r[6] * n + r[10] * v, this.normalize();
  }
  divide(e) {
    return this.x /= e.x, this.y /= e.y, this.z /= e.z, this;
  }
  divideScalar(e) {
    return this.multiplyScalar(1 / e);
  }
  min(e) {
    return this.x = Math.min(this.x, e.x), this.y = Math.min(this.y, e.y), this.z = Math.min(this.z, e.z), this;
  }
  max(e) {
    return this.x = Math.max(this.x, e.x), this.y = Math.max(this.y, e.y), this.z = Math.max(this.z, e.z), this;
  }
  clamp(e, f) {
    return this.x = pf(this.x, e.x, f.x), this.y = pf(this.y, e.y, f.y), this.z = pf(this.z, e.z, f.z), this;
  }
  clampScalar(e, f) {
    return this.x = pf(this.x, e, f), this.y = pf(this.y, e, f), this.z = pf(this.z, e, f), this;
  }
  clampLength(e, f) {
    const n = this.length();
    return this.divideScalar(n || 1).multiplyScalar(pf(n, e, f));
  }
  floor() {
    return this.x = Math.floor(this.x), this.y = Math.floor(this.y), this.z = Math.floor(this.z), this;
  }
  ceil() {
    return this.x = Math.ceil(this.x), this.y = Math.ceil(this.y), this.z = Math.ceil(this.z), this;
  }
  round() {
    return this.x = Math.round(this.x), this.y = Math.round(this.y), this.z = Math.round(this.z), this;
  }
  roundToZero() {
    return this.x = Math.trunc(this.x), this.y = Math.trunc(this.y), this.z = Math.trunc(this.z), this;
  }
  negate() {
    return this.x = -this.x, this.y = -this.y, this.z = -this.z, this;
  }
  dot(e) {
    return this.x * e.x + this.y * e.y + this.z * e.z;
  }
  lengthSq() {
    return this.x * this.x + this.y * this.y + this.z * this.z;
  }
  length() {
    return Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
  }
  manhattanLength() {
    return Math.abs(this.x) + Math.abs(this.y) + Math.abs(this.z);
  }
  normalize() {
    return this.divideScalar(this.length() || 1);
  }
  setLength(e) {
    return this.normalize().multiplyScalar(e);
  }
  lerp(e, f) {
    return this.x += (e.x - this.x) * f, this.y += (e.y - this.y) * f, this.z += (e.z - this.z) * f, this;
  }
  lerpVectors(e, f, n) {
    return this.x = e.x + (f.x - e.x) * n, this.y = e.y + (f.y - e.y) * n, this.z = e.z + (f.z - e.z) * n, this;
  }
  cross(e) {
    return this.crossVectors(this, e);
  }
  crossVectors(e, f) {
    const n = e.x, v = e.y, r = e.z, z = f.x, u = f.y, b = f.z;
    return this.x = v * b - r * u, this.y = r * z - n * b, this.z = n * u - v * z, this;
  }
  projectOnVector(e) {
    const f = e.lengthSq();
    if (f === 0) return this.set(0, 0, 0);
    const n = e.dot(this) / f;
    return this.copy(e).multiplyScalar(n);
  }
  projectOnPlane(e) {
    return tM.copy(this).projectOnVector(e), this.sub(tM);
  }
  reflect(e) {
    return this.sub(tM.copy(e).multiplyScalar(2 * this.dot(e)));
  }
  angleTo(e) {
    const f = Math.sqrt(this.lengthSq() * e.lengthSq());
    if (f === 0) return Math.PI / 2;
    const n = this.dot(e) / f;
    return Math.acos(pf(n, -1, 1));
  }
  distanceTo(e) {
    return Math.sqrt(this.distanceToSquared(e));
  }
  distanceToSquared(e) {
    const f = this.x - e.x, n = this.y - e.y, v = this.z - e.z;
    return f * f + n * n + v * v;
  }
  manhattanDistanceTo(e) {
    return Math.abs(this.x - e.x) + Math.abs(this.y - e.y) + Math.abs(this.z - e.z);
  }
  setFromSpherical(e) {
    return this.setFromSphericalCoords(e.radius, e.phi, e.theta);
  }
  setFromSphericalCoords(e, f, n) {
    const v = Math.sin(f) * e;
    return this.x = v * Math.sin(n), this.y = Math.cos(f) * e, this.z = v * Math.cos(n), this;
  }
  setFromCylindrical(e) {
    return this.setFromCylindricalCoords(e.radius, e.theta, e.y);
  }
  setFromCylindricalCoords(e, f, n) {
    return this.x = e * Math.sin(f), this.y = n, this.z = e * Math.cos(f), this;
  }
  setFromMatrixPosition(e) {
    const f = e.elements;
    return this.x = f[12], this.y = f[13], this.z = f[14], this;
  }
  setFromMatrixScale(e) {
    const f = this.setFromMatrixColumn(e, 0).length(), n = this.setFromMatrixColumn(e, 1).length(), v = this.setFromMatrixColumn(e, 2).length();
    return this.x = f, this.y = n, this.z = v, this;
  }
  setFromMatrixColumn(e, f) {
    return this.fromArray(e.elements, f * 4);
  }
  setFromMatrix3Column(e, f) {
    return this.fromArray(e.elements, f * 3);
  }
  setFromEuler(e) {
    return this.x = e._x, this.y = e._y, this.z = e._z, this;
  }
  setFromColor(e) {
    return this.x = e.r, this.y = e.g, this.z = e.b, this;
  }
  equals(e) {
    return e.x === this.x && e.y === this.y && e.z === this.z;
  }
  fromArray(e, f = 0) {
    return this.x = e[f], this.y = e[f + 1], this.z = e[f + 2], this;
  }
  toArray(e = [], f = 0) {
    return e[f] = this.x, e[f + 1] = this.y, e[f + 2] = this.z, e;
  }
  fromBufferAttribute(e, f) {
    return this.x = e.getX(f), this.y = e.getY(f), this.z = e.getZ(f), this;
  }
  random() {
    return this.x = Math.random(), this.y = Math.random(), this.z = Math.random(), this;
  }
  randomDirection() {
    const e = Math.random() * Math.PI * 2, f = Math.random() * 2 - 1, n = Math.sqrt(1 - f * f);
    return this.x = n * Math.cos(e), this.y = f, this.z = n * Math.sin(e), this;
  }
  *[Symbol.iterator]() {
    yield this.x, yield this.y, yield this.z;
  }
}
const tM = new ht();
const $I = new Mb();
class Tf {
  constructor(e, f, n, v, r, z, u, b, a) {
    Tf.prototype.isMatrix3 = true, this.elements = [1, 0, 0, 0, 1, 0, 0, 0, 1], e !== void 0 && this.set(e, f, n, v, r, z, u, b, a);
  }
  set(e, f, n, v, r, z, u, b, a) {
    const P = this.elements;
    return P[0] = e, P[1] = v, P[2] = u, P[3] = f, P[4] = r, P[5] = b, P[6] = n, P[7] = z, P[8] = a, this;
  }
  identity() {
    return this.set(1, 0, 0, 0, 1, 0, 0, 0, 1), this;
  }
  copy(e) {
    const f = this.elements, n = e.elements;
    return f[0] = n[0], f[1] = n[1], f[2] = n[2], f[3] = n[3], f[4] = n[4], f[5] = n[5], f[6] = n[6], f[7] = n[7], f[8] = n[8], this;
  }
  extractBasis(e, f, n) {
    return e.setFromMatrix3Column(this, 0), f.setFromMatrix3Column(this, 1), n.setFromMatrix3Column(this, 2), this;
  }
  setFromMatrix4(e) {
    const f = e.elements;
    return this.set(f[0], f[4], f[8], f[1], f[5], f[9], f[2], f[6], f[10]), this;
  }
  multiply(e) {
    return this.multiplyMatrices(this, e);
  }
  premultiply(e) {
    return this.multiplyMatrices(e, this);
  }
  multiplyMatrices(e, f) {
    const n = e.elements, v = f.elements, r = this.elements, z = n[0], u = n[3], b = n[6], a = n[1], P = n[4], s = n[7], d = n[2], c = n[5], l = n[8], W = v[0], X = v[3], y = v[6], m = v[1], j = v[4], x = v[7], L = v[2], O = v[5], N = v[8];
    return r[0] = z * W + u * m + b * L, r[3] = z * X + u * j + b * O, r[6] = z * y + u * x + b * N, r[1] = a * W + P * m + s * L, r[4] = a * X + P * j + s * O, r[7] = a * y + P * x + s * N, r[2] = d * W + c * m + l * L, r[5] = d * X + c * j + l * O, r[8] = d * y + c * x + l * N, this;
  }
  multiplyScalar(e) {
    const f = this.elements;
    return f[0] *= e, f[3] *= e, f[6] *= e, f[1] *= e, f[4] *= e, f[7] *= e, f[2] *= e, f[5] *= e, f[8] *= e, this;
  }
  determinant() {
    const e = this.elements, f = e[0], n = e[1], v = e[2], r = e[3], z = e[4], u = e[5], b = e[6], a = e[7], P = e[8];
    return f * z * P - f * u * a - n * r * P + n * u * b + v * r * a - v * z * b;
  }
  invert() {
    const e = this.elements, f = e[0], n = e[1], v = e[2], r = e[3], z = e[4], u = e[5], b = e[6], a = e[7], P = e[8], s = P * z - u * a, d = u * b - P * r, c = a * r - z * b, l = f * s + n * d + v * c;
    if (l === 0) return this.set(0, 0, 0, 0, 0, 0, 0, 0, 0);
    const W = 1 / l;
    return e[0] = s * W, e[1] = (v * a - P * n) * W, e[2] = (u * n - v * z) * W, e[3] = d * W, e[4] = (P * f - v * b) * W, e[5] = (v * r - u * f) * W, e[6] = c * W, e[7] = (n * b - a * f) * W, e[8] = (z * f - n * r) * W, this;
  }
  transpose() {
    let e;
    const f = this.elements;
    return e = f[1], f[1] = f[3], f[3] = e, e = f[2], f[2] = f[6], f[6] = e, e = f[5], f[5] = f[7], f[7] = e, this;
  }
  getNormalMatrix(e) {
    return this.setFromMatrix4(e).invert().transpose();
  }
  transposeIntoArray(e) {
    const f = this.elements;
    return e[0] = f[0], e[1] = f[3], e[2] = f[6], e[3] = f[1], e[4] = f[4], e[5] = f[7], e[6] = f[2], e[7] = f[5], e[8] = f[8], this;
  }
  setUvTransform(e, f, n, v, r, z, u) {
    const b = Math.cos(r), a = Math.sin(r);
    return this.set(n * b, n * a, -n * (b * z + a * u) + z + e, -v * a, v * b, -v * (-a * z + b * u) + u + f, 0, 0, 1), this;
  }
  scale(e, f) {
    return this.premultiply(eM.makeScale(e, f)), this;
  }
  rotate(e) {
    return this.premultiply(eM.makeRotation(-e)), this;
  }
  translate(e, f) {
    return this.premultiply(eM.makeTranslation(e, f)), this;
  }
  makeTranslation(e, f) {
    return e.isVector2 ? this.set(1, 0, e.x, 0, 1, e.y, 0, 0, 1) : this.set(1, 0, e, 0, 1, f, 0, 0, 1), this;
  }
  makeRotation(e) {
    const f = Math.cos(e), n = Math.sin(e);
    return this.set(f, -n, 0, n, f, 0, 0, 0, 1), this;
  }
  makeScale(e, f) {
    return this.set(e, 0, 0, 0, f, 0, 0, 0, 1), this;
  }
  equals(e) {
    const f = this.elements, n = e.elements;
    for (let v = 0; v < 9; v++) if (f[v] !== n[v]) return false;
    return true;
  }
  fromArray(e, f = 0) {
    for (let n = 0; n < 9; n++) this.elements[n] = e[n + f];
    return this;
  }
  toArray(e = [], f = 0) {
    const n = this.elements;
    return e[f] = n[0], e[f + 1] = n[1], e[f + 2] = n[2], e[f + 3] = n[3], e[f + 4] = n[4], e[f + 5] = n[5], e[f + 6] = n[6], e[f + 7] = n[7], e[f + 8] = n[8], e;
  }
  clone() {
    return new this.constructor().fromArray(this.elements);
  }
}
const eM = new Tf();
function tK(t) {
  for (let e = t.length - 1; e >= 0; --e) if (t[e] >= 65535) return true;
  return false;
}
function P6(t) {
  return document.createElementNS("http://www.w3.org/1999/xhtml", t);
}
function Kqt() {
  const t = P6("canvas");
  return t.style.display = "block", t;
}
const eK = {};
function os(t) {
  t in eK || (eK[t] = true, console.warn(t));
}
function Eqt(t, e, f) {
  return new Promise(function(n, v) {
    function r() {
      switch (t.clientWaitSync(e, t.SYNC_FLUSH_COMMANDS_BIT, 0)) {
        case t.WAIT_FAILED:
          v();
          break;
        case t.TIMEOUT_EXPIRED:
          setTimeout(r, f);
          break;
        default:
          n();
      }
    }
    setTimeout(r, f);
  });
}
const fK = new Tf().set(0.4123908, 0.3575843, 0.1804808, 0.212639, 0.7151687, 0.0721923, 0.0193308, 0.1191948, 0.9505322);
const nK = new Tf().set(3.2409699, -1.5373832, -0.4986108, -0.9692436, 1.8759675, 0.0415551, 0.0556301, -0.203977, 1.0569715);
function Cqt() {
  const t = { enabled: true, workingColorSpace: Ua, spaces: {}, convert: function(v, r, z) {
    return this.enabled === false || r === z || !r || !z || (this.spaces[r].transfer === dn && (v.r = d9(v.r), v.g = d9(v.g), v.b = d9(v.b)), this.spaces[r].primaries !== this.spaces[z].primaries && (v.applyMatrix3(this.spaces[r].toXYZ), v.applyMatrix3(this.spaces[z].fromXYZ)), this.spaces[z].transfer === dn && (v.r = Ea(v.r), v.g = Ea(v.g), v.b = Ea(v.b))), v;
  }, workingToColorSpace: function(v, r) {
    return this.convert(v, this.workingColorSpace, r);
  }, colorSpaceToWorking: function(v, r) {
    return this.convert(v, r, this.workingColorSpace);
  }, getPrimaries: function(v) {
    return this.spaces[v].primaries;
  }, getTransfer: function(v) {
    return v === T5 ? u6 : this.spaces[v].transfer;
  }, getToneMappingMode: function(v) {
    return this.spaces[v].outputColorSpaceConfig.toneMappingMode || "standard";
  }, getLuminanceCoefficients: function(v, r = this.workingColorSpace) {
    return v.fromArray(this.spaces[r].luminanceCoefficients);
  }, define: function(v) {
    Object.assign(this.spaces, v);
  }, _getMatrix: function(v, r, z) {
    return v.copy(this.spaces[r].toXYZ).multiply(this.spaces[z].fromXYZ);
  }, _getDrawingBufferColorSpace: function(v) {
    return this.spaces[v].outputColorSpaceConfig.drawingBufferColorSpace;
  }, _getUnpackColorSpace: function(v = this.workingColorSpace) {
    return this.spaces[v].workingColorSpaceConfig.unpackColorSpace;
  }, fromWorkingColorSpace: function(v, r) {
    return os("THREE.ColorManagement: .fromWorkingColorSpace() has been renamed to .workingToColorSpace()."), t.workingToColorSpace(v, r);
  }, toWorkingColorSpace: function(v, r) {
    return os("THREE.ColorManagement: .toWorkingColorSpace() has been renamed to .colorSpaceToWorking()."), t.colorSpaceToWorking(v, r);
  } }, e = [0.64, 0.33, 0.3, 0.6, 0.15, 0.06], f = [0.2126, 0.7152, 0.0722], n = [0.3127, 0.329];
  return t.define({ [Ua]: { primaries: e, whitePoint: n, transfer: u6, toXYZ: fK, fromXYZ: nK, luminanceCoefficients: f, workingColorSpaceConfig: { unpackColorSpace: Qv }, outputColorSpaceConfig: { drawingBufferColorSpace: Qv } }, [Qv]: { primaries: e, whitePoint: n, transfer: dn, toXYZ: fK, fromXYZ: nK, luminanceCoefficients: f, outputColorSpaceConfig: { drawingBufferColorSpace: Qv } } }), t;
}
const Uf = Cqt();
function d9(t) {
  return t < 0.04045 ? t * 0.0773993808 : Math.pow(t * 0.9478672986 + 0.0521327014, 2.4);
}
function Ea(t) {
  return t < 31308e-7 ? t * 12.92 : 1.055 * Math.pow(t, 0.41666) - 0.055;
}
let Ca;
class Aqt {
  static getDataURL(e, f = "image/png") {
    if (/^data:/i.test(e.src) || typeof HTMLCanvasElement > "u") return e.src;
    let n;
    if (e instanceof HTMLCanvasElement) n = e;
    else {
      Ca === void 0 && (Ca = P6("canvas")), Ca.width = e.width, Ca.height = e.height;
      const v = Ca.getContext("2d");
      e instanceof ImageData ? v.putImageData(e, 0, 0) : v.drawImage(e, 0, 0, e.width, e.height), n = Ca;
    }
    return n.toDataURL(f);
  }
  static sRGBToLinear(e) {
    if (typeof HTMLImageElement < "u" && e instanceof HTMLImageElement || typeof HTMLCanvasElement < "u" && e instanceof HTMLCanvasElement || typeof ImageBitmap < "u" && e instanceof ImageBitmap) {
      const f = P6("canvas");
      f.width = e.width, f.height = e.height;
      const n = f.getContext("2d");
      n.drawImage(e, 0, 0, e.width, e.height);
      const v = n.getImageData(0, 0, e.width, e.height), r = v.data;
      for (let z = 0; z < r.length; z++) r[z] = d9(r[z] / 255) * 255;
      return n.putImageData(v, 0, 0), f;
    } else if (e.data) {
      const f = e.data.slice(0);
      for (let n = 0; n < f.length; n++) f instanceof Uint8Array || f instanceof Uint8ClampedArray ? f[n] = Math.floor(d9(f[n] / 255) * 255) : f[n] = d9(f[n]);
      return { data: f, width: e.width, height: e.height };
    } else return console.warn("THREE.ImageUtils.sRGBToLinear(): Unsupported image type. No color space conversion applied."), e;
  }
}
let Qqt = 0;
class fM {
  constructor(e = null) {
    this.isSource = true, Object.defineProperty(this, "id", { value: Qqt++ }), this.uuid = jz(), this.data = e, this.dataReady = true, this.version = 0;
  }
  getSize(e) {
    const f = this.data;
    return typeof HTMLVideoElement < "u" && f instanceof HTMLVideoElement ? e.set(f.videoWidth, f.videoHeight, 0) : f instanceof VideoFrame ? e.set(f.displayHeight, f.displayWidth, 0) : f !== null ? e.set(f.width, f.height, f.depth || 0) : e.set(0, 0, 0), e;
  }
  set needsUpdate(e) {
    e === true && this.version++;
  }
  toJSON(e) {
    const f = e === void 0 || typeof e == "string";
    if (!f && e.images[this.uuid] !== void 0) return e.images[this.uuid];
    const n = { uuid: this.uuid, url: "" }, v = this.data;
    if (v !== null) {
      let r;
      if (Array.isArray(v)) {
        r = [];
        for (let z = 0, u = v.length; z < u; z++) v[z].isDataTexture ? r.push(nM(v[z].image)) : r.push(nM(v[z]));
      } else r = nM(v);
      n.url = r;
    }
    return f || (e.images[this.uuid] = n), n;
  }
}
function nM(t) {
  return typeof HTMLImageElement < "u" && t instanceof HTMLImageElement || typeof HTMLCanvasElement < "u" && t instanceof HTMLCanvasElement || typeof ImageBitmap < "u" && t instanceof ImageBitmap ? Aqt.getDataURL(t) : t.data ? { data: Array.from(t.data), width: t.width, height: t.height, type: t.data.constructor.name } : (console.warn("THREE.Texture: Unable to serialize Texture."), {});
}
let _qt = 0;
const vM = new ht();
class Tv extends Nb {
  constructor(e = Tv.DEFAULT_IMAGE, f = Tv.DEFAULT_MAPPING, n = Lb, v = Lb, r = Wz, z = Ob, u = W7, b = yz, a = Tv.DEFAULT_ANISOTROPY, P = T5) {
    super(), this.isTexture = true, Object.defineProperty(this, "id", { value: _qt++ }), this.uuid = jz(), this.name = "", this.source = new fM(e), this.mipmaps = [], this.mapping = f, this.channel = 0, this.wrapS = n, this.wrapT = v, this.magFilter = r, this.minFilter = z, this.anisotropy = a, this.format = u, this.internalFormat = null, this.type = b, this.offset = new Me(0, 0), this.repeat = new Me(1, 1), this.center = new Me(0, 0), this.rotation = 0, this.matrixAutoUpdate = true, this.matrix = new Tf(), this.generateMipmaps = true, this.premultiplyAlpha = false, this.flipY = true, this.unpackAlignment = 4, this.colorSpace = P, this.userData = {}, this.updateRanges = [], this.version = 0, this.onUpdate = null, this.renderTarget = null, this.isRenderTargetTexture = false, this.isArrayTexture = !!(e && e.depth && e.depth > 1), this.pmremVersion = 0;
  }
  get width() {
    return this.source.getSize(vM).x;
  }
  get height() {
    return this.source.getSize(vM).y;
  }
  get depth() {
    return this.source.getSize(vM).z;
  }
  get image() {
    return this.source.data;
  }
  set image(e = null) {
    this.source.data = e;
  }
  updateMatrix() {
    this.matrix.setUvTransform(this.offset.x, this.offset.y, this.repeat.x, this.repeat.y, this.rotation, this.center.x, this.center.y);
  }
  addUpdateRange(e, f) {
    this.updateRanges.push({ start: e, count: f });
  }
  clearUpdateRanges() {
    this.updateRanges.length = 0;
  }
  clone() {
    return new this.constructor().copy(this);
  }
  copy(e) {
    return this.name = e.name, this.source = e.source, this.mipmaps = e.mipmaps.slice(0), this.mapping = e.mapping, this.channel = e.channel, this.wrapS = e.wrapS, this.wrapT = e.wrapT, this.magFilter = e.magFilter, this.minFilter = e.minFilter, this.anisotropy = e.anisotropy, this.format = e.format, this.internalFormat = e.internalFormat, this.type = e.type, this.offset.copy(e.offset), this.repeat.copy(e.repeat), this.center.copy(e.center), this.rotation = e.rotation, this.matrixAutoUpdate = e.matrixAutoUpdate, this.matrix.copy(e.matrix), this.generateMipmaps = e.generateMipmaps, this.premultiplyAlpha = e.premultiplyAlpha, this.flipY = e.flipY, this.unpackAlignment = e.unpackAlignment, this.colorSpace = e.colorSpace, this.renderTarget = e.renderTarget, this.isRenderTargetTexture = e.isRenderTargetTexture, this.isArrayTexture = e.isArrayTexture, this.userData = JSON.parse(JSON.stringify(e.userData)), this.needsUpdate = true, this;
  }
  setValues(e) {
    for (const f in e) {
      const n = e[f];
      if (n === void 0) {
        console.warn(`THREE.Texture.setValues(): parameter '${f}' has value of undefined.`);
        continue;
      }
      const v = this[f];
      if (v === void 0) {
        console.warn(`THREE.Texture.setValues(): property '${f}' does not exist.`);
        continue;
      }
      v && n && v.isVector2 && n.isVector2 || v && n && v.isVector3 && n.isVector3 || v && n && v.isMatrix3 && n.isMatrix3 ? v.copy(n) : this[f] = n;
    }
  }
  toJSON(e) {
    const f = e === void 0 || typeof e == "string";
    if (!f && e.textures[this.uuid] !== void 0) return e.textures[this.uuid];
    const n = { metadata: { version: 4.7, type: "Texture", generator: "Texture.toJSON" }, uuid: this.uuid, name: this.name, image: this.source.toJSON(e).uuid, mapping: this.mapping, channel: this.channel, repeat: [this.repeat.x, this.repeat.y], offset: [this.offset.x, this.offset.y], center: [this.center.x, this.center.y], rotation: this.rotation, wrap: [this.wrapS, this.wrapT], format: this.format, internalFormat: this.internalFormat, type: this.type, colorSpace: this.colorSpace, minFilter: this.minFilter, magFilter: this.magFilter, anisotropy: this.anisotropy, flipY: this.flipY, generateMipmaps: this.generateMipmaps, premultiplyAlpha: this.premultiplyAlpha, unpackAlignment: this.unpackAlignment };
    return Object.keys(this.userData).length > 0 && (n.userData = this.userData), f || (e.textures[this.uuid] = n), n;
  }
  dispose() {
    this.dispatchEvent({ type: "dispose" });
  }
  transformUv(e) {
    if (this.mapping !== kI) return e;
    if (e.applyMatrix3(this.matrix), e.x < 0 || e.x > 1) switch (this.wrapS) {
      case e6:
        e.x = e.x - Math.floor(e.x);
        break;
      case Lb:
        e.x = e.x < 0 ? 0 : 1;
        break;
      case XN:
        Math.abs(Math.floor(e.x) % 2) === 1 ? e.x = Math.ceil(e.x) - e.x : e.x = e.x - Math.floor(e.x);
        break;
    }
    if (e.y < 0 || e.y > 1) switch (this.wrapT) {
      case e6:
        e.y = e.y - Math.floor(e.y);
        break;
      case Lb:
        e.y = e.y < 0 ? 0 : 1;
        break;
      case XN:
        Math.abs(Math.floor(e.y) % 2) === 1 ? e.y = Math.ceil(e.y) - e.y : e.y = e.y - Math.floor(e.y);
        break;
    }
    return this.flipY && (e.y = 1 - e.y), e;
  }
  set needsUpdate(e) {
    e === true && (this.version++, this.source.needsUpdate = true);
  }
  set needsPMREMUpdate(e) {
    e === true && this.pmremVersion++;
  }
}
Tv.DEFAULT_IMAGE = null, Tv.DEFAULT_MAPPING = kI, Tv.DEFAULT_ANISOTROPY = 1;
class Un {
  constructor(e = 0, f = 0, n = 0, v = 1) {
    Un.prototype.isVector4 = true, this.x = e, this.y = f, this.z = n, this.w = v;
  }
  get width() {
    return this.z;
  }
  set width(e) {
    this.z = e;
  }
  get height() {
    return this.w;
  }
  set height(e) {
    this.w = e;
  }
  set(e, f, n, v) {
    return this.x = e, this.y = f, this.z = n, this.w = v, this;
  }
  setScalar(e) {
    return this.x = e, this.y = e, this.z = e, this.w = e, this;
  }
  setX(e) {
    return this.x = e, this;
  }
  setY(e) {
    return this.y = e, this;
  }
  setZ(e) {
    return this.z = e, this;
  }
  setW(e) {
    return this.w = e, this;
  }
  setComponent(e, f) {
    switch (e) {
      case 0:
        this.x = f;
        break;
      case 1:
        this.y = f;
        break;
      case 2:
        this.z = f;
        break;
      case 3:
        this.w = f;
        break;
      default:
        throw new Error("index is out of range: " + e);
    }
    return this;
  }
  getComponent(e) {
    switch (e) {
      case 0:
        return this.x;
      case 1:
        return this.y;
      case 2:
        return this.z;
      case 3:
        return this.w;
      default:
        throw new Error("index is out of range: " + e);
    }
  }
  clone() {
    return new this.constructor(this.x, this.y, this.z, this.w);
  }
  copy(e) {
    return this.x = e.x, this.y = e.y, this.z = e.z, this.w = e.w !== void 0 ? e.w : 1, this;
  }
  add(e) {
    return this.x += e.x, this.y += e.y, this.z += e.z, this.w += e.w, this;
  }
  addScalar(e) {
    return this.x += e, this.y += e, this.z += e, this.w += e, this;
  }
  addVectors(e, f) {
    return this.x = e.x + f.x, this.y = e.y + f.y, this.z = e.z + f.z, this.w = e.w + f.w, this;
  }
  addScaledVector(e, f) {
    return this.x += e.x * f, this.y += e.y * f, this.z += e.z * f, this.w += e.w * f, this;
  }
  sub(e) {
    return this.x -= e.x, this.y -= e.y, this.z -= e.z, this.w -= e.w, this;
  }
  subScalar(e) {
    return this.x -= e, this.y -= e, this.z -= e, this.w -= e, this;
  }
  subVectors(e, f) {
    return this.x = e.x - f.x, this.y = e.y - f.y, this.z = e.z - f.z, this.w = e.w - f.w, this;
  }
  multiply(e) {
    return this.x *= e.x, this.y *= e.y, this.z *= e.z, this.w *= e.w, this;
  }
  multiplyScalar(e) {
    return this.x *= e, this.y *= e, this.z *= e, this.w *= e, this;
  }
  applyMatrix4(e) {
    const f = this.x, n = this.y, v = this.z, r = this.w, z = e.elements;
    return this.x = z[0] * f + z[4] * n + z[8] * v + z[12] * r, this.y = z[1] * f + z[5] * n + z[9] * v + z[13] * r, this.z = z[2] * f + z[6] * n + z[10] * v + z[14] * r, this.w = z[3] * f + z[7] * n + z[11] * v + z[15] * r, this;
  }
  divide(e) {
    return this.x /= e.x, this.y /= e.y, this.z /= e.z, this.w /= e.w, this;
  }
  divideScalar(e) {
    return this.multiplyScalar(1 / e);
  }
  setAxisAngleFromQuaternion(e) {
    this.w = 2 * Math.acos(e.w);
    const f = Math.sqrt(1 - e.w * e.w);
    return f < 1e-4 ? (this.x = 1, this.y = 0, this.z = 0) : (this.x = e.x / f, this.y = e.y / f, this.z = e.z / f), this;
  }
  setAxisAngleFromRotationMatrix(e) {
    let f, n, v, r;
    const b = e.elements, a = b[0], P = b[4], s = b[8], d = b[1], c = b[5], l = b[9], W = b[2], X = b[6], y = b[10];
    if (Math.abs(P - d) < 0.01 && Math.abs(s - W) < 0.01 && Math.abs(l - X) < 0.01) {
      if (Math.abs(P + d) < 0.1 && Math.abs(s + W) < 0.1 && Math.abs(l + X) < 0.1 && Math.abs(a + c + y - 3) < 0.1) return this.set(1, 0, 0, 0), this;
      f = Math.PI;
      const j = (a + 1) / 2, x = (c + 1) / 2, L = (y + 1) / 2, O = (P + d) / 4, N = (s + W) / 4, i = (l + X) / 4;
      return j > x && j > L ? j < 0.01 ? (n = 0, v = 0.707106781, r = 0.707106781) : (n = Math.sqrt(j), v = O / n, r = N / n) : x > L ? x < 0.01 ? (n = 0.707106781, v = 0, r = 0.707106781) : (v = Math.sqrt(x), n = O / v, r = i / v) : L < 0.01 ? (n = 0.707106781, v = 0.707106781, r = 0) : (r = Math.sqrt(L), n = N / r, v = i / r), this.set(n, v, r, f), this;
    }
    let m = Math.sqrt((X - l) * (X - l) + (s - W) * (s - W) + (d - P) * (d - P));
    return Math.abs(m) < 1e-3 && (m = 1), this.x = (X - l) / m, this.y = (s - W) / m, this.z = (d - P) / m, this.w = Math.acos((a + c + y - 1) / 2), this;
  }
  setFromMatrixPosition(e) {
    const f = e.elements;
    return this.x = f[12], this.y = f[13], this.z = f[14], this.w = f[15], this;
  }
  min(e) {
    return this.x = Math.min(this.x, e.x), this.y = Math.min(this.y, e.y), this.z = Math.min(this.z, e.z), this.w = Math.min(this.w, e.w), this;
  }
  max(e) {
    return this.x = Math.max(this.x, e.x), this.y = Math.max(this.y, e.y), this.z = Math.max(this.z, e.z), this.w = Math.max(this.w, e.w), this;
  }
  clamp(e, f) {
    return this.x = pf(this.x, e.x, f.x), this.y = pf(this.y, e.y, f.y), this.z = pf(this.z, e.z, f.z), this.w = pf(this.w, e.w, f.w), this;
  }
  clampScalar(e, f) {
    return this.x = pf(this.x, e, f), this.y = pf(this.y, e, f), this.z = pf(this.z, e, f), this.w = pf(this.w, e, f), this;
  }
  clampLength(e, f) {
    const n = this.length();
    return this.divideScalar(n || 1).multiplyScalar(pf(n, e, f));
  }
  floor() {
    return this.x = Math.floor(this.x), this.y = Math.floor(this.y), this.z = Math.floor(this.z), this.w = Math.floor(this.w), this;
  }
  ceil() {
    return this.x = Math.ceil(this.x), this.y = Math.ceil(this.y), this.z = Math.ceil(this.z), this.w = Math.ceil(this.w), this;
  }
  round() {
    return this.x = Math.round(this.x), this.y = Math.round(this.y), this.z = Math.round(this.z), this.w = Math.round(this.w), this;
  }
  roundToZero() {
    return this.x = Math.trunc(this.x), this.y = Math.trunc(this.y), this.z = Math.trunc(this.z), this.w = Math.trunc(this.w), this;
  }
  negate() {
    return this.x = -this.x, this.y = -this.y, this.z = -this.z, this.w = -this.w, this;
  }
  dot(e) {
    return this.x * e.x + this.y * e.y + this.z * e.z + this.w * e.w;
  }
  lengthSq() {
    return this.x * this.x + this.y * this.y + this.z * this.z + this.w * this.w;
  }
  length() {
    return Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z + this.w * this.w);
  }
  manhattanLength() {
    return Math.abs(this.x) + Math.abs(this.y) + Math.abs(this.z) + Math.abs(this.w);
  }
  normalize() {
    return this.divideScalar(this.length() || 1);
  }
  setLength(e) {
    return this.normalize().multiplyScalar(e);
  }
  lerp(e, f) {
    return this.x += (e.x - this.x) * f, this.y += (e.y - this.y) * f, this.z += (e.z - this.z) * f, this.w += (e.w - this.w) * f, this;
  }
  lerpVectors(e, f, n) {
    return this.x = e.x + (f.x - e.x) * n, this.y = e.y + (f.y - e.y) * n, this.z = e.z + (f.z - e.z) * n, this.w = e.w + (f.w - e.w) * n, this;
  }
  equals(e) {
    return e.x === this.x && e.y === this.y && e.z === this.z && e.w === this.w;
  }
  fromArray(e, f = 0) {
    return this.x = e[f], this.y = e[f + 1], this.z = e[f + 2], this.w = e[f + 3], this;
  }
  toArray(e = [], f = 0) {
    return e[f] = this.x, e[f + 1] = this.y, e[f + 2] = this.z, e[f + 3] = this.w, e;
  }
  fromBufferAttribute(e, f) {
    return this.x = e.getX(f), this.y = e.getY(f), this.z = e.getZ(f), this.w = e.getW(f), this;
  }
  random() {
    return this.x = Math.random(), this.y = Math.random(), this.z = Math.random(), this.w = Math.random(), this;
  }
  *[Symbol.iterator]() {
    yield this.x, yield this.y, yield this.z, yield this.w;
  }
}
class $qt extends Nb {
  constructor(e = 1, f = 1, n = {}) {
    super(), n = Object.assign({ generateMipmaps: false, internalFormat: null, minFilter: Wz, depthBuffer: true, stencilBuffer: false, resolveDepthBuffer: true, resolveStencilBuffer: true, depthTexture: null, samples: 0, count: 1, depth: 1, multiview: false }, n), this.isRenderTarget = true, this.width = e, this.height = f, this.depth = n.depth, this.scissor = new Un(0, 0, e, f), this.scissorTest = false, this.viewport = new Un(0, 0, e, f);
    const v = { width: e, height: f, depth: n.depth }, r = new Tv(v);
    this.textures = [];
    const z = n.count;
    for (let u = 0; u < z; u++) this.textures[u] = r.clone(), this.textures[u].isRenderTargetTexture = true, this.textures[u].renderTarget = this;
    this._setTextureOptions(n), this.depthBuffer = n.depthBuffer, this.stencilBuffer = n.stencilBuffer, this.resolveDepthBuffer = n.resolveDepthBuffer, this.resolveStencilBuffer = n.resolveStencilBuffer, this._depthTexture = null, this.depthTexture = n.depthTexture, this.samples = n.samples, this.multiview = n.multiview;
  }
  _setTextureOptions(e = {}) {
    const f = { minFilter: Wz, generateMipmaps: false, flipY: false, internalFormat: null };
    e.mapping !== void 0 && (f.mapping = e.mapping), e.wrapS !== void 0 && (f.wrapS = e.wrapS), e.wrapT !== void 0 && (f.wrapT = e.wrapT), e.wrapR !== void 0 && (f.wrapR = e.wrapR), e.magFilter !== void 0 && (f.magFilter = e.magFilter), e.minFilter !== void 0 && (f.minFilter = e.minFilter), e.format !== void 0 && (f.format = e.format), e.type !== void 0 && (f.type = e.type), e.anisotropy !== void 0 && (f.anisotropy = e.anisotropy), e.colorSpace !== void 0 && (f.colorSpace = e.colorSpace), e.flipY !== void 0 && (f.flipY = e.flipY), e.generateMipmaps !== void 0 && (f.generateMipmaps = e.generateMipmaps), e.internalFormat !== void 0 && (f.internalFormat = e.internalFormat);
    for (let n = 0; n < this.textures.length; n++) this.textures[n].setValues(f);
  }
  get texture() {
    return this.textures[0];
  }
  set texture(e) {
    this.textures[0] = e;
  }
  set depthTexture(e) {
    this._depthTexture !== null && (this._depthTexture.renderTarget = null), e !== null && (e.renderTarget = this), this._depthTexture = e;
  }
  get depthTexture() {
    return this._depthTexture;
  }
  setSize(e, f, n = 1) {
    if (this.width !== e || this.height !== f || this.depth !== n) {
      this.width = e, this.height = f, this.depth = n;
      for (let v = 0, r = this.textures.length; v < r; v++) this.textures[v].image.width = e, this.textures[v].image.height = f, this.textures[v].image.depth = n, this.textures[v].isArrayTexture = this.textures[v].image.depth > 1;
      this.dispose();
    }
    this.viewport.set(0, 0, e, f), this.scissor.set(0, 0, e, f);
  }
  clone() {
    return new this.constructor().copy(this);
  }
  copy(e) {
    this.width = e.width, this.height = e.height, this.depth = e.depth, this.scissor.copy(e.scissor), this.scissorTest = e.scissorTest, this.viewport.copy(e.viewport), this.textures.length = 0;
    for (let f = 0, n = e.textures.length; f < n; f++) {
      this.textures[f] = e.textures[f].clone(), this.textures[f].isRenderTargetTexture = true, this.textures[f].renderTarget = this;
      const v = Object.assign({}, e.textures[f].image);
      this.textures[f].source = new fM(v);
    }
    return this.depthBuffer = e.depthBuffer, this.stencilBuffer = e.stencilBuffer, this.resolveDepthBuffer = e.resolveDepthBuffer, this.resolveStencilBuffer = e.resolveStencilBuffer, e.depthTexture !== null && (this.depthTexture = e.depthTexture.clone()), this.samples = e.samples, this;
  }
  dispose() {
    this.dispatchEvent({ type: "dispose" });
  }
}
class ib extends $qt {
  constructor(e = 1, f = 1, n = {}) {
    super(e, f, n), this.isWebGLRenderTarget = true;
  }
}
class vK extends Tv {
  constructor(e = null, f = 1, n = 1, v = 1) {
    super(null), this.isDataArrayTexture = true, this.image = { data: e, width: f, height: n, depth: v }, this.magFilter = tr, this.minFilter = tr, this.wrapR = Lb, this.generateMipmaps = false, this.flipY = false, this.unpackAlignment = 1, this.layerUpdates = /* @__PURE__ */ new Set();
  }
  addLayerUpdate(e) {
    this.layerUpdates.add(e);
  }
  clearLayerUpdates() {
    this.layerUpdates.clear();
  }
}
class tFt extends Tv {
  constructor(e = null, f = 1, n = 1, v = 1) {
    super(null), this.isData3DTexture = true, this.image = { data: e, width: f, height: n, depth: v }, this.magFilter = tr, this.minFilter = tr, this.wrapR = Lb, this.generateMipmaps = false, this.flipY = false, this.unpackAlignment = 1;
  }
}
class c9 {
  constructor(e = new ht(1 / 0, 1 / 0, 1 / 0), f = new ht(-1 / 0, -1 / 0, -1 / 0)) {
    this.isBox3 = true, this.min = e, this.max = f;
  }
  set(e, f) {
    return this.min.copy(e), this.max.copy(f), this;
  }
  setFromArray(e) {
    this.makeEmpty();
    for (let f = 0, n = e.length; f < n; f += 3) this.expandByPoint(Z7.fromArray(e, f));
    return this;
  }
  setFromBufferAttribute(e) {
    this.makeEmpty();
    for (let f = 0, n = e.count; f < n; f++) this.expandByPoint(Z7.fromBufferAttribute(e, f));
    return this;
  }
  setFromPoints(e) {
    this.makeEmpty();
    for (let f = 0, n = e.length; f < n; f++) this.expandByPoint(e[f]);
    return this;
  }
  setFromCenterAndSize(e, f) {
    const n = Z7.copy(f).multiplyScalar(0.5);
    return this.min.copy(e).sub(n), this.max.copy(e).add(n), this;
  }
  setFromObject(e, f = false) {
    return this.makeEmpty(), this.expandByObject(e, f);
  }
  clone() {
    return new this.constructor().copy(this);
  }
  copy(e) {
    return this.min.copy(e.min), this.max.copy(e.max), this;
  }
  makeEmpty() {
    return this.min.x = this.min.y = this.min.z = 1 / 0, this.max.x = this.max.y = this.max.z = -1 / 0, this;
  }
  isEmpty() {
    return this.max.x < this.min.x || this.max.y < this.min.y || this.max.z < this.min.z;
  }
  getCenter(e) {
    return this.isEmpty() ? e.set(0, 0, 0) : e.addVectors(this.min, this.max).multiplyScalar(0.5);
  }
  getSize(e) {
    return this.isEmpty() ? e.set(0, 0, 0) : e.subVectors(this.max, this.min);
  }
  expandByPoint(e) {
    return this.min.min(e), this.max.max(e), this;
  }
  expandByVector(e) {
    return this.min.sub(e), this.max.add(e), this;
  }
  expandByScalar(e) {
    return this.min.addScalar(-e), this.max.addScalar(e), this;
  }
  expandByObject(e, f = false) {
    e.updateWorldMatrix(false, false);
    const n = e.geometry;
    if (n !== void 0) {
      const r = n.getAttribute("position");
      if (f === true && r !== void 0 && e.isInstancedMesh !== true) for (let z = 0, u = r.count; z < u; z++) e.isMesh === true ? e.getVertexPosition(z, Z7) : Z7.fromBufferAttribute(r, z), Z7.applyMatrix4(e.matrixWorld), this.expandByPoint(Z7);
      else e.boundingBox !== void 0 ? (e.boundingBox === null && e.computeBoundingBox(), s6.copy(e.boundingBox)) : (n.boundingBox === null && n.computeBoundingBox(), s6.copy(n.boundingBox)), s6.applyMatrix4(e.matrixWorld), this.union(s6);
    }
    const v = e.children;
    for (let r = 0, z = v.length; r < z; r++) this.expandByObject(v[r], f);
    return this;
  }
  containsPoint(e) {
    return e.x >= this.min.x && e.x <= this.max.x && e.y >= this.min.y && e.y <= this.max.y && e.z >= this.min.z && e.z <= this.max.z;
  }
  containsBox(e) {
    return this.min.x <= e.min.x && e.max.x <= this.max.x && this.min.y <= e.min.y && e.max.y <= this.max.y && this.min.z <= e.min.z && e.max.z <= this.max.z;
  }
  getParameter(e, f) {
    return f.set((e.x - this.min.x) / (this.max.x - this.min.x), (e.y - this.min.y) / (this.max.y - this.min.y), (e.z - this.min.z) / (this.max.z - this.min.z));
  }
  intersectsBox(e) {
    return e.max.x >= this.min.x && e.min.x <= this.max.x && e.max.y >= this.min.y && e.min.y <= this.max.y && e.max.z >= this.min.z && e.min.z <= this.max.z;
  }
  intersectsSphere(e) {
    return this.clampPoint(e.center, Z7), Z7.distanceToSquared(e.center) <= e.radius * e.radius;
  }
  intersectsPlane(e) {
    let f, n;
    return e.normal.x > 0 ? (f = e.normal.x * this.min.x, n = e.normal.x * this.max.x) : (f = e.normal.x * this.max.x, n = e.normal.x * this.min.x), e.normal.y > 0 ? (f += e.normal.y * this.min.y, n += e.normal.y * this.max.y) : (f += e.normal.y * this.max.y, n += e.normal.y * this.min.y), e.normal.z > 0 ? (f += e.normal.z * this.min.z, n += e.normal.z * this.max.z) : (f += e.normal.z * this.max.z, n += e.normal.z * this.min.z), f <= -e.constant && n >= -e.constant;
  }
  intersectsTriangle(e) {
    if (this.isEmpty()) return false;
    this.getCenter(ws), d6.subVectors(this.max, ws), Aa.subVectors(e.a, ws), Qa.subVectors(e.b, ws), _a.subVectors(e.c, ws), o5.subVectors(Qa, Aa), w5.subVectors(_a, Qa), pb.subVectors(Aa, _a);
    let f = [0, -o5.z, o5.y, 0, -w5.z, w5.y, 0, -pb.z, pb.y, o5.z, 0, -o5.x, w5.z, 0, -w5.x, pb.z, 0, -pb.x, -o5.y, o5.x, 0, -w5.y, w5.x, 0, -pb.y, pb.x, 0];
    return !rM(f, Aa, Qa, _a, d6) || (f = [1, 0, 0, 0, 1, 0, 0, 0, 1], !rM(f, Aa, Qa, _a, d6)) ? false : (c6.crossVectors(o5, w5), f = [c6.x, c6.y, c6.z], rM(f, Aa, Qa, _a, d6));
  }
  clampPoint(e, f) {
    return f.copy(e).clamp(this.min, this.max);
  }
  distanceToPoint(e) {
    return this.clampPoint(e, Z7).distanceTo(e);
  }
  getBoundingSphere(e) {
    return this.isEmpty() ? e.makeEmpty() : (this.getCenter(e.center), e.radius = this.getSize(Z7).length() * 0.5), e;
  }
  intersect(e) {
    return this.min.max(e.min), this.max.min(e.max), this.isEmpty() && this.makeEmpty(), this;
  }
  union(e) {
    return this.min.min(e.min), this.max.max(e.max), this;
  }
  applyMatrix4(e) {
    return this.isEmpty() ? this : (l9[0].set(this.min.x, this.min.y, this.min.z).applyMatrix4(e), l9[1].set(this.min.x, this.min.y, this.max.z).applyMatrix4(e), l9[2].set(this.min.x, this.max.y, this.min.z).applyMatrix4(e), l9[3].set(this.min.x, this.max.y, this.max.z).applyMatrix4(e), l9[4].set(this.max.x, this.min.y, this.min.z).applyMatrix4(e), l9[5].set(this.max.x, this.min.y, this.max.z).applyMatrix4(e), l9[6].set(this.max.x, this.max.y, this.min.z).applyMatrix4(e), l9[7].set(this.max.x, this.max.y, this.max.z).applyMatrix4(e), this.setFromPoints(l9), this);
  }
  translate(e) {
    return this.min.add(e), this.max.add(e), this;
  }
  equals(e) {
    return e.min.equals(this.min) && e.max.equals(this.max);
  }
  toJSON() {
    return { min: this.min.toArray(), max: this.max.toArray() };
  }
  fromJSON(e) {
    return this.min.fromArray(e.min), this.max.fromArray(e.max), this;
  }
}
const l9 = [new ht(), new ht(), new ht(), new ht(), new ht(), new ht(), new ht(), new ht()];
const Z7 = new ht();
const s6 = new c9();
const Aa = new ht();
const Qa = new ht();
const _a = new ht();
const o5 = new ht();
const w5 = new ht();
const pb = new ht();
const ws = new ht();
const d6 = new ht();
const c6 = new ht();
const Tb = new ht();
function rM(t, e, f, n, v) {
  for (let r = 0, z = t.length - 3; r <= z; r += 3) {
    Tb.fromArray(t, r);
    const u = v.x * Math.abs(Tb.x) + v.y * Math.abs(Tb.y) + v.z * Math.abs(Tb.z), b = e.dot(Tb), a = f.dot(Tb), P = n.dot(Tb);
    if (Math.max(-Math.max(b, a, P), Math.min(b, a, P)) > u) return false;
  }
  return true;
}
const eFt = new c9();
const hs = new ht();
const zM = new ht();
class $a {
  constructor(e = new ht(), f = -1) {
    this.isSphere = true, this.center = e, this.radius = f;
  }
  set(e, f) {
    return this.center.copy(e), this.radius = f, this;
  }
  setFromPoints(e, f) {
    const n = this.center;
    f !== void 0 ? n.copy(f) : eFt.setFromPoints(e).getCenter(n);
    let v = 0;
    for (let r = 0, z = e.length; r < z; r++) v = Math.max(v, n.distanceToSquared(e[r]));
    return this.radius = Math.sqrt(v), this;
  }
  copy(e) {
    return this.center.copy(e.center), this.radius = e.radius, this;
  }
  isEmpty() {
    return this.radius < 0;
  }
  makeEmpty() {
    return this.center.set(0, 0, 0), this.radius = -1, this;
  }
  containsPoint(e) {
    return e.distanceToSquared(this.center) <= this.radius * this.radius;
  }
  distanceToPoint(e) {
    return e.distanceTo(this.center) - this.radius;
  }
  intersectsSphere(e) {
    const f = this.radius + e.radius;
    return e.center.distanceToSquared(this.center) <= f * f;
  }
  intersectsBox(e) {
    return e.intersectsSphere(this);
  }
  intersectsPlane(e) {
    return Math.abs(e.distanceToPoint(this.center)) <= this.radius;
  }
  clampPoint(e, f) {
    const n = this.center.distanceToSquared(e);
    return f.copy(e), n > this.radius * this.radius && (f.sub(this.center).normalize(), f.multiplyScalar(this.radius).add(this.center)), f;
  }
  getBoundingBox(e) {
    return this.isEmpty() ? (e.makeEmpty(), e) : (e.set(this.center, this.center), e.expandByScalar(this.radius), e);
  }
  applyMatrix4(e) {
    return this.center.applyMatrix4(e), this.radius = this.radius * e.getMaxScaleOnAxis(), this;
  }
  translate(e) {
    return this.center.add(e), this;
  }
  expandByPoint(e) {
    if (this.isEmpty()) return this.center.copy(e), this.radius = 0, this;
    hs.subVectors(e, this.center);
    const f = hs.lengthSq();
    if (f > this.radius * this.radius) {
      const n = Math.sqrt(f), v = (n - this.radius) * 0.5;
      this.center.addScaledVector(hs, v / n), this.radius += v;
    }
    return this;
  }
  union(e) {
    return e.isEmpty() ? this : this.isEmpty() ? (this.copy(e), this) : (this.center.equals(e.center) === true ? this.radius = Math.max(this.radius, e.radius) : (zM.subVectors(e.center, this.center).setLength(e.radius), this.expandByPoint(hs.copy(e.center).add(zM)), this.expandByPoint(hs.copy(e.center).sub(zM))), this);
  }
  equals(e) {
    return e.center.equals(this.center) && e.radius === this.radius;
  }
  clone() {
    return new this.constructor().copy(this);
  }
  toJSON() {
    return { radius: this.radius, center: this.center.toArray() };
  }
  fromJSON(e) {
    return this.radius = e.radius, this.center.fromArray(e.center), this;
  }
}
const X9 = new ht();
const uM = new ht();
const l6 = new ht();
const h5 = new ht();
const bM = new ht();
const X6 = new ht();
const aM = new ht();
class W6 {
  constructor(e = new ht(), f = new ht(0, 0, -1)) {
    this.origin = e, this.direction = f;
  }
  set(e, f) {
    return this.origin.copy(e), this.direction.copy(f), this;
  }
  copy(e) {
    return this.origin.copy(e.origin), this.direction.copy(e.direction), this;
  }
  at(e, f) {
    return f.copy(this.origin).addScaledVector(this.direction, e);
  }
  lookAt(e) {
    return this.direction.copy(e).sub(this.origin).normalize(), this;
  }
  recast(e) {
    return this.origin.copy(this.at(e, X9)), this;
  }
  closestPointToPoint(e, f) {
    f.subVectors(e, this.origin);
    const n = f.dot(this.direction);
    return n < 0 ? f.copy(this.origin) : f.copy(this.origin).addScaledVector(this.direction, n);
  }
  distanceToPoint(e) {
    return Math.sqrt(this.distanceSqToPoint(e));
  }
  distanceSqToPoint(e) {
    const f = X9.subVectors(e, this.origin).dot(this.direction);
    return f < 0 ? this.origin.distanceToSquared(e) : (X9.copy(this.origin).addScaledVector(this.direction, f), X9.distanceToSquared(e));
  }
  distanceSqToSegment(e, f, n, v) {
    uM.copy(e).add(f).multiplyScalar(0.5), l6.copy(f).sub(e).normalize(), h5.copy(this.origin).sub(uM);
    const r = e.distanceTo(f) * 0.5, z = -this.direction.dot(l6), u = h5.dot(this.direction), b = -h5.dot(l6), a = h5.lengthSq(), P = Math.abs(1 - z * z);
    let s, d, c, l;
    if (P > 0) if (s = z * b - u, d = z * u - b, l = r * P, s >= 0) if (d >= -l) if (d <= l) {
      const W = 1 / P;
      s *= W, d *= W, c = s * (s + z * d + 2 * u) + d * (z * s + d + 2 * b) + a;
    } else d = r, s = Math.max(0, -(z * d + u)), c = -s * s + d * (d + 2 * b) + a;
    else d = -r, s = Math.max(0, -(z * d + u)), c = -s * s + d * (d + 2 * b) + a;
    else d <= -l ? (s = Math.max(0, -(-z * r + u)), d = s > 0 ? -r : Math.min(Math.max(-r, -b), r), c = -s * s + d * (d + 2 * b) + a) : d <= l ? (s = 0, d = Math.min(Math.max(-r, -b), r), c = d * (d + 2 * b) + a) : (s = Math.max(0, -(z * r + u)), d = s > 0 ? r : Math.min(Math.max(-r, -b), r), c = -s * s + d * (d + 2 * b) + a);
    else d = z > 0 ? -r : r, s = Math.max(0, -(z * d + u)), c = -s * s + d * (d + 2 * b) + a;
    return n && n.copy(this.origin).addScaledVector(this.direction, s), v && v.copy(uM).addScaledVector(l6, d), c;
  }
  intersectSphere(e, f) {
    X9.subVectors(e.center, this.origin);
    const n = X9.dot(this.direction), v = X9.dot(X9) - n * n, r = e.radius * e.radius;
    if (v > r) return null;
    const z = Math.sqrt(r - v), u = n - z, b = n + z;
    return b < 0 ? null : u < 0 ? this.at(b, f) : this.at(u, f);
  }
  intersectsSphere(e) {
    return e.radius < 0 ? false : this.distanceSqToPoint(e.center) <= e.radius * e.radius;
  }
  distanceToPlane(e) {
    const f = e.normal.dot(this.direction);
    if (f === 0) return e.distanceToPoint(this.origin) === 0 ? 0 : null;
    const n = -(this.origin.dot(e.normal) + e.constant) / f;
    return n >= 0 ? n : null;
  }
  intersectPlane(e, f) {
    const n = this.distanceToPlane(e);
    return n === null ? null : this.at(n, f);
  }
  intersectsPlane(e) {
    const f = e.distanceToPoint(this.origin);
    return f === 0 || e.normal.dot(this.direction) * f < 0;
  }
  intersectBox(e, f) {
    let n, v, r, z, u, b;
    const a = 1 / this.direction.x, P = 1 / this.direction.y, s = 1 / this.direction.z, d = this.origin;
    return a >= 0 ? (n = (e.min.x - d.x) * a, v = (e.max.x - d.x) * a) : (n = (e.max.x - d.x) * a, v = (e.min.x - d.x) * a), P >= 0 ? (r = (e.min.y - d.y) * P, z = (e.max.y - d.y) * P) : (r = (e.max.y - d.y) * P, z = (e.min.y - d.y) * P), n > z || r > v || ((r > n || isNaN(n)) && (n = r), (z < v || isNaN(v)) && (v = z), s >= 0 ? (u = (e.min.z - d.z) * s, b = (e.max.z - d.z) * s) : (u = (e.max.z - d.z) * s, b = (e.min.z - d.z) * s), n > b || u > v) || ((u > n || n !== n) && (n = u), (b < v || v !== v) && (v = b), v < 0) ? null : this.at(n >= 0 ? n : v, f);
  }
  intersectsBox(e) {
    return this.intersectBox(e, X9) !== null;
  }
  intersectTriangle(e, f, n, v, r) {
    bM.subVectors(f, e), X6.subVectors(n, e), aM.crossVectors(bM, X6);
    let z = this.direction.dot(aM), u;
    if (z > 0) {
      if (v) return null;
      u = 1;
    } else if (z < 0) u = -1, z = -z;
    else return null;
    h5.subVectors(this.origin, e);
    const b = u * this.direction.dot(X6.crossVectors(h5, X6));
    if (b < 0) return null;
    const a = u * this.direction.dot(bM.cross(h5));
    if (a < 0 || b + a > z) return null;
    const P = -u * h5.dot(aM);
    return P < 0 ? null : this.at(P / z, r);
  }
  applyMatrix4(e) {
    return this.origin.applyMatrix4(e), this.direction.transformDirection(e), this;
  }
  equals(e) {
    return e.origin.equals(this.origin) && e.direction.equals(this.direction);
  }
  clone() {
    return new this.constructor().copy(this);
  }
}
class cn {
  constructor(e, f, n, v, r, z, u, b, a, P, s, d, c, l, W, X) {
    cn.prototype.isMatrix4 = true, this.elements = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1], e !== void 0 && this.set(e, f, n, v, r, z, u, b, a, P, s, d, c, l, W, X);
  }
  set(e, f, n, v, r, z, u, b, a, P, s, d, c, l, W, X) {
    const y = this.elements;
    return y[0] = e, y[4] = f, y[8] = n, y[12] = v, y[1] = r, y[5] = z, y[9] = u, y[13] = b, y[2] = a, y[6] = P, y[10] = s, y[14] = d, y[3] = c, y[7] = l, y[11] = W, y[15] = X, this;
  }
  identity() {
    return this.set(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1), this;
  }
  clone() {
    return new cn().fromArray(this.elements);
  }
  copy(e) {
    const f = this.elements, n = e.elements;
    return f[0] = n[0], f[1] = n[1], f[2] = n[2], f[3] = n[3], f[4] = n[4], f[5] = n[5], f[6] = n[6], f[7] = n[7], f[8] = n[8], f[9] = n[9], f[10] = n[10], f[11] = n[11], f[12] = n[12], f[13] = n[13], f[14] = n[14], f[15] = n[15], this;
  }
  copyPosition(e) {
    const f = this.elements, n = e.elements;
    return f[12] = n[12], f[13] = n[13], f[14] = n[14], this;
  }
  setFromMatrix3(e) {
    const f = e.elements;
    return this.set(f[0], f[3], f[6], 0, f[1], f[4], f[7], 0, f[2], f[5], f[8], 0, 0, 0, 0, 1), this;
  }
  extractBasis(e, f, n) {
    return e.setFromMatrixColumn(this, 0), f.setFromMatrixColumn(this, 1), n.setFromMatrixColumn(this, 2), this;
  }
  makeBasis(e, f, n) {
    return this.set(e.x, f.x, n.x, 0, e.y, f.y, n.y, 0, e.z, f.z, n.z, 0, 0, 0, 0, 1), this;
  }
  extractRotation(e) {
    const f = this.elements, n = e.elements, v = 1 / t1.setFromMatrixColumn(e, 0).length(), r = 1 / t1.setFromMatrixColumn(e, 1).length(), z = 1 / t1.setFromMatrixColumn(e, 2).length();
    return f[0] = n[0] * v, f[1] = n[1] * v, f[2] = n[2] * v, f[3] = 0, f[4] = n[4] * r, f[5] = n[5] * r, f[6] = n[6] * r, f[7] = 0, f[8] = n[8] * z, f[9] = n[9] * z, f[10] = n[10] * z, f[11] = 0, f[12] = 0, f[13] = 0, f[14] = 0, f[15] = 1, this;
  }
  makeRotationFromEuler(e) {
    const f = this.elements, n = e.x, v = e.y, r = e.z, z = Math.cos(n), u = Math.sin(n), b = Math.cos(v), a = Math.sin(v), P = Math.cos(r), s = Math.sin(r);
    if (e.order === "XYZ") {
      const d = z * P, c = z * s, l = u * P, W = u * s;
      f[0] = b * P, f[4] = -b * s, f[8] = a, f[1] = c + l * a, f[5] = d - W * a, f[9] = -u * b, f[2] = W - d * a, f[6] = l + c * a, f[10] = z * b;
    } else if (e.order === "YXZ") {
      const d = b * P, c = b * s, l = a * P, W = a * s;
      f[0] = d + W * u, f[4] = l * u - c, f[8] = z * a, f[1] = z * s, f[5] = z * P, f[9] = -u, f[2] = c * u - l, f[6] = W + d * u, f[10] = z * b;
    } else if (e.order === "ZXY") {
      const d = b * P, c = b * s, l = a * P, W = a * s;
      f[0] = d - W * u, f[4] = -z * s, f[8] = l + c * u, f[1] = c + l * u, f[5] = z * P, f[9] = W - d * u, f[2] = -z * a, f[6] = u, f[10] = z * b;
    } else if (e.order === "ZYX") {
      const d = z * P, c = z * s, l = u * P, W = u * s;
      f[0] = b * P, f[4] = l * a - c, f[8] = d * a + W, f[1] = b * s, f[5] = W * a + d, f[9] = c * a - l, f[2] = -a, f[6] = u * b, f[10] = z * b;
    } else if (e.order === "YZX") {
      const d = z * b, c = z * a, l = u * b, W = u * a;
      f[0] = b * P, f[4] = W - d * s, f[8] = l * s + c, f[1] = s, f[5] = z * P, f[9] = -u * P, f[2] = -a * P, f[6] = c * s + l, f[10] = d - W * s;
    } else if (e.order === "XZY") {
      const d = z * b, c = z * a, l = u * b, W = u * a;
      f[0] = b * P, f[4] = -s, f[8] = a * P, f[1] = d * s + W, f[5] = z * P, f[9] = c * s - l, f[2] = l * s - c, f[6] = u * P, f[10] = W * s + d;
    }
    return f[3] = 0, f[7] = 0, f[11] = 0, f[12] = 0, f[13] = 0, f[14] = 0, f[15] = 1, this;
  }
  makeRotationFromQuaternion(e) {
    return this.compose(fFt, e, nFt);
  }
  lookAt(e, f, n) {
    const v = this.elements;
    return er.subVectors(e, f), er.lengthSq() === 0 && (er.z = 1), er.normalize(), G5.crossVectors(n, er), G5.lengthSq() === 0 && (Math.abs(n.z) === 1 ? er.x += 1e-4 : er.z += 1e-4, er.normalize(), G5.crossVectors(n, er)), G5.normalize(), y6.crossVectors(er, G5), v[0] = G5.x, v[4] = y6.x, v[8] = er.x, v[1] = G5.y, v[5] = y6.y, v[9] = er.y, v[2] = G5.z, v[6] = y6.z, v[10] = er.z, this;
  }
  multiply(e) {
    return this.multiplyMatrices(this, e);
  }
  premultiply(e) {
    return this.multiplyMatrices(e, this);
  }
  multiplyMatrices(e, f) {
    const n = e.elements, v = f.elements, r = this.elements, z = n[0], u = n[4], b = n[8], a = n[12], P = n[1], s = n[5], d = n[9], c = n[13], l = n[2], W = n[6], X = n[10], y = n[14], m = n[3], j = n[7], x = n[11], L = n[15], O = v[0], N = v[4], i = v[8], H = v[12], M = v[1], p = v[5], T = v[9], w = v[13], Y = v[2], q = v[6], D = v[10], V = v[14], G = v[3], R = v[7], k = v[11], K = v[15];
    return r[0] = z * O + u * M + b * Y + a * G, r[4] = z * N + u * p + b * q + a * R, r[8] = z * i + u * T + b * D + a * k, r[12] = z * H + u * w + b * V + a * K, r[1] = P * O + s * M + d * Y + c * G, r[5] = P * N + s * p + d * q + c * R, r[9] = P * i + s * T + d * D + c * k, r[13] = P * H + s * w + d * V + c * K, r[2] = l * O + W * M + X * Y + y * G, r[6] = l * N + W * p + X * q + y * R, r[10] = l * i + W * T + X * D + y * k, r[14] = l * H + W * w + X * V + y * K, r[3] = m * O + j * M + x * Y + L * G, r[7] = m * N + j * p + x * q + L * R, r[11] = m * i + j * T + x * D + L * k, r[15] = m * H + j * w + x * V + L * K, this;
  }
  multiplyScalar(e) {
    const f = this.elements;
    return f[0] *= e, f[4] *= e, f[8] *= e, f[12] *= e, f[1] *= e, f[5] *= e, f[9] *= e, f[13] *= e, f[2] *= e, f[6] *= e, f[10] *= e, f[14] *= e, f[3] *= e, f[7] *= e, f[11] *= e, f[15] *= e, this;
  }
  determinant() {
    const e = this.elements, f = e[0], n = e[4], v = e[8], r = e[12], z = e[1], u = e[5], b = e[9], a = e[13], P = e[2], s = e[6], d = e[10], c = e[14], l = e[3], W = e[7], X = e[11], y = e[15];
    return l * (+r * b * s - v * a * s - r * u * d + n * a * d + v * u * c - n * b * c) + W * (+f * b * c - f * a * d + r * z * d - v * z * c + v * a * P - r * b * P) + X * (+f * a * s - f * u * c - r * z * s + n * z * c + r * u * P - n * a * P) + y * (-v * u * P - f * b * s + f * u * d + v * z * s - n * z * d + n * b * P);
  }
  transpose() {
    const e = this.elements;
    let f;
    return f = e[1], e[1] = e[4], e[4] = f, f = e[2], e[2] = e[8], e[8] = f, f = e[6], e[6] = e[9], e[9] = f, f = e[3], e[3] = e[12], e[12] = f, f = e[7], e[7] = e[13], e[13] = f, f = e[11], e[11] = e[14], e[14] = f, this;
  }
  setPosition(e, f, n) {
    const v = this.elements;
    return e.isVector3 ? (v[12] = e.x, v[13] = e.y, v[14] = e.z) : (v[12] = e, v[13] = f, v[14] = n), this;
  }
  invert() {
    const e = this.elements, f = e[0], n = e[1], v = e[2], r = e[3], z = e[4], u = e[5], b = e[6], a = e[7], P = e[8], s = e[9], d = e[10], c = e[11], l = e[12], W = e[13], X = e[14], y = e[15], m = s * X * a - W * d * a + W * b * c - u * X * c - s * b * y + u * d * y, j = l * d * a - P * X * a - l * b * c + z * X * c + P * b * y - z * d * y, x = P * W * a - l * s * a + l * u * c - z * W * c - P * u * y + z * s * y, L = l * s * b - P * W * b - l * u * d + z * W * d + P * u * X - z * s * X, O = f * m + n * j + v * x + r * L;
    if (O === 0) return this.set(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    const N = 1 / O;
    return e[0] = m * N, e[1] = (W * d * r - s * X * r - W * v * c + n * X * c + s * v * y - n * d * y) * N, e[2] = (u * X * r - W * b * r + W * v * a - n * X * a - u * v * y + n * b * y) * N, e[3] = (s * b * r - u * d * r - s * v * a + n * d * a + u * v * c - n * b * c) * N, e[4] = j * N, e[5] = (P * X * r - l * d * r + l * v * c - f * X * c - P * v * y + f * d * y) * N, e[6] = (l * b * r - z * X * r - l * v * a + f * X * a + z * v * y - f * b * y) * N, e[7] = (z * d * r - P * b * r + P * v * a - f * d * a - z * v * c + f * b * c) * N, e[8] = x * N, e[9] = (l * s * r - P * W * r - l * n * c + f * W * c + P * n * y - f * s * y) * N, e[10] = (z * W * r - l * u * r + l * n * a - f * W * a - z * n * y + f * u * y) * N, e[11] = (P * u * r - z * s * r - P * n * a + f * s * a + z * n * c - f * u * c) * N, e[12] = L * N, e[13] = (P * W * v - l * s * v + l * n * d - f * W * d - P * n * X + f * s * X) * N, e[14] = (l * u * v - z * W * v - l * n * b + f * W * b + z * n * X - f * u * X) * N, e[15] = (z * s * v - P * u * v + P * n * b - f * s * b - z * n * d + f * u * d) * N, this;
  }
  scale(e) {
    const f = this.elements, n = e.x, v = e.y, r = e.z;
    return f[0] *= n, f[4] *= v, f[8] *= r, f[1] *= n, f[5] *= v, f[9] *= r, f[2] *= n, f[6] *= v, f[10] *= r, f[3] *= n, f[7] *= v, f[11] *= r, this;
  }
  getMaxScaleOnAxis() {
    const e = this.elements, f = e[0] * e[0] + e[1] * e[1] + e[2] * e[2], n = e[4] * e[4] + e[5] * e[5] + e[6] * e[6], v = e[8] * e[8] + e[9] * e[9] + e[10] * e[10];
    return Math.sqrt(Math.max(f, n, v));
  }
  makeTranslation(e, f, n) {
    return e.isVector3 ? this.set(1, 0, 0, e.x, 0, 1, 0, e.y, 0, 0, 1, e.z, 0, 0, 0, 1) : this.set(1, 0, 0, e, 0, 1, 0, f, 0, 0, 1, n, 0, 0, 0, 1), this;
  }
  makeRotationX(e) {
    const f = Math.cos(e), n = Math.sin(e);
    return this.set(1, 0, 0, 0, 0, f, -n, 0, 0, n, f, 0, 0, 0, 0, 1), this;
  }
  makeRotationY(e) {
    const f = Math.cos(e), n = Math.sin(e);
    return this.set(f, 0, n, 0, 0, 1, 0, 0, -n, 0, f, 0, 0, 0, 0, 1), this;
  }
  makeRotationZ(e) {
    const f = Math.cos(e), n = Math.sin(e);
    return this.set(f, -n, 0, 0, n, f, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1), this;
  }
  makeRotationAxis(e, f) {
    const n = Math.cos(f), v = Math.sin(f), r = 1 - n, z = e.x, u = e.y, b = e.z, a = r * z, P = r * u;
    return this.set(a * z + n, a * u - v * b, a * b + v * u, 0, a * u + v * b, P * u + n, P * b - v * z, 0, a * b - v * u, P * b + v * z, r * b * b + n, 0, 0, 0, 0, 1), this;
  }
  makeScale(e, f, n) {
    return this.set(e, 0, 0, 0, 0, f, 0, 0, 0, 0, n, 0, 0, 0, 0, 1), this;
  }
  makeShear(e, f, n, v, r, z) {
    return this.set(1, n, r, 0, e, 1, z, 0, f, v, 1, 0, 0, 0, 0, 1), this;
  }
  compose(e, f, n) {
    const v = this.elements, r = f._x, z = f._y, u = f._z, b = f._w, a = r + r, P = z + z, s = u + u, d = r * a, c = r * P, l = r * s, W = z * P, X = z * s, y = u * s, m = b * a, j = b * P, x = b * s, L = n.x, O = n.y, N = n.z;
    return v[0] = (1 - (W + y)) * L, v[1] = (c + x) * L, v[2] = (l - j) * L, v[3] = 0, v[4] = (c - x) * O, v[5] = (1 - (d + y)) * O, v[6] = (X + m) * O, v[7] = 0, v[8] = (l + j) * N, v[9] = (X - m) * N, v[10] = (1 - (d + W)) * N, v[11] = 0, v[12] = e.x, v[13] = e.y, v[14] = e.z, v[15] = 1, this;
  }
  decompose(e, f, n) {
    const v = this.elements;
    let r = t1.set(v[0], v[1], v[2]).length();
    const z = t1.set(v[4], v[5], v[6]).length(), u = t1.set(v[8], v[9], v[10]).length();
    this.determinant() < 0 && (r = -r), e.x = v[12], e.y = v[13], e.z = v[14], m7.copy(this);
    const a = 1 / r, P = 1 / z, s = 1 / u;
    return m7.elements[0] *= a, m7.elements[1] *= a, m7.elements[2] *= a, m7.elements[4] *= P, m7.elements[5] *= P, m7.elements[6] *= P, m7.elements[8] *= s, m7.elements[9] *= s, m7.elements[10] *= s, f.setFromRotationMatrix(m7), n.x = r, n.y = z, n.z = u, this;
  }
  makePerspective(e, f, n, v, r, z, u = mz, b = false) {
    const a = this.elements, P = 2 * r / (f - e), s = 2 * r / (n - v), d = (f + e) / (f - e), c = (n + v) / (n - v);
    let l, W;
    if (b) l = r / (z - r), W = z * r / (z - r);
    else if (u === mz) l = -(z + r) / (z - r), W = -2 * z * r / (z - r);
    else if (u === b6) l = -z / (z - r), W = -z * r / (z - r);
    else throw new Error("THREE.Matrix4.makePerspective(): Invalid coordinate system: " + u);
    return a[0] = P, a[4] = 0, a[8] = d, a[12] = 0, a[1] = 0, a[5] = s, a[9] = c, a[13] = 0, a[2] = 0, a[6] = 0, a[10] = l, a[14] = W, a[3] = 0, a[7] = 0, a[11] = -1, a[15] = 0, this;
  }
  makeOrthographic(e, f, n, v, r, z, u = mz, b = false) {
    const a = this.elements, P = 2 / (f - e), s = 2 / (n - v), d = -(f + e) / (f - e), c = -(n + v) / (n - v);
    let l, W;
    if (b) l = 1 / (z - r), W = z / (z - r);
    else if (u === mz) l = -2 / (z - r), W = -(z + r) / (z - r);
    else if (u === b6) l = -1 / (z - r), W = -r / (z - r);
    else throw new Error("THREE.Matrix4.makeOrthographic(): Invalid coordinate system: " + u);
    return a[0] = P, a[4] = 0, a[8] = 0, a[12] = d, a[1] = 0, a[5] = s, a[9] = 0, a[13] = c, a[2] = 0, a[6] = 0, a[10] = l, a[14] = W, a[3] = 0, a[7] = 0, a[11] = 0, a[15] = 1, this;
  }
  equals(e) {
    const f = this.elements, n = e.elements;
    for (let v = 0; v < 16; v++) if (f[v] !== n[v]) return false;
    return true;
  }
  fromArray(e, f = 0) {
    for (let n = 0; n < 16; n++) this.elements[n] = e[n + f];
    return this;
  }
  toArray(e = [], f = 0) {
    const n = this.elements;
    return e[f] = n[0], e[f + 1] = n[1], e[f + 2] = n[2], e[f + 3] = n[3], e[f + 4] = n[4], e[f + 5] = n[5], e[f + 6] = n[6], e[f + 7] = n[7], e[f + 8] = n[8], e[f + 9] = n[9], e[f + 10] = n[10], e[f + 11] = n[11], e[f + 12] = n[12], e[f + 13] = n[13], e[f + 14] = n[14], e[f + 15] = n[15], e;
  }
}
const t1 = new ht();
const m7 = new cn();
const fFt = new ht(0, 0, 0);
const nFt = new ht(1, 1, 1);
const G5 = new ht();
const y6 = new ht();
const er = new ht();
const rK = new cn();
const zK = new Mb();
class xz {
  constructor(e = 0, f = 0, n = 0, v = xz.DEFAULT_ORDER) {
    this.isEuler = true, this._x = e, this._y = f, this._z = n, this._order = v;
  }
  get x() {
    return this._x;
  }
  set x(e) {
    this._x = e, this._onChangeCallback();
  }
  get y() {
    return this._y;
  }
  set y(e) {
    this._y = e, this._onChangeCallback();
  }
  get z() {
    return this._z;
  }
  set z(e) {
    this._z = e, this._onChangeCallback();
  }
  get order() {
    return this._order;
  }
  set order(e) {
    this._order = e, this._onChangeCallback();
  }
  set(e, f, n, v = this._order) {
    return this._x = e, this._y = f, this._z = n, this._order = v, this._onChangeCallback(), this;
  }
  clone() {
    return new this.constructor(this._x, this._y, this._z, this._order);
  }
  copy(e) {
    return this._x = e._x, this._y = e._y, this._z = e._z, this._order = e._order, this._onChangeCallback(), this;
  }
  setFromRotationMatrix(e, f = this._order, n = true) {
    const v = e.elements, r = v[0], z = v[4], u = v[8], b = v[1], a = v[5], P = v[9], s = v[2], d = v[6], c = v[10];
    switch (f) {
      case "XYZ":
        this._y = Math.asin(pf(u, -1, 1)), Math.abs(u) < 0.9999999 ? (this._x = Math.atan2(-P, c), this._z = Math.atan2(-z, r)) : (this._x = Math.atan2(d, a), this._z = 0);
        break;
      case "YXZ":
        this._x = Math.asin(-pf(P, -1, 1)), Math.abs(P) < 0.9999999 ? (this._y = Math.atan2(u, c), this._z = Math.atan2(b, a)) : (this._y = Math.atan2(-s, r), this._z = 0);
        break;
      case "ZXY":
        this._x = Math.asin(pf(d, -1, 1)), Math.abs(d) < 0.9999999 ? (this._y = Math.atan2(-s, c), this._z = Math.atan2(-z, a)) : (this._y = 0, this._z = Math.atan2(b, r));
        break;
      case "ZYX":
        this._y = Math.asin(-pf(s, -1, 1)), Math.abs(s) < 0.9999999 ? (this._x = Math.atan2(d, c), this._z = Math.atan2(b, r)) : (this._x = 0, this._z = Math.atan2(-z, a));
        break;
      case "YZX":
        this._z = Math.asin(pf(b, -1, 1)), Math.abs(b) < 0.9999999 ? (this._x = Math.atan2(-P, a), this._y = Math.atan2(-s, r)) : (this._x = 0, this._y = Math.atan2(u, c));
        break;
      case "XZY":
        this._z = Math.asin(-pf(z, -1, 1)), Math.abs(z) < 0.9999999 ? (this._x = Math.atan2(d, a), this._y = Math.atan2(u, r)) : (this._x = Math.atan2(-P, c), this._y = 0);
        break;
      default:
        console.warn("THREE.Euler: .setFromRotationMatrix() encountered an unknown order: " + f);
    }
    return this._order = f, n === true && this._onChangeCallback(), this;
  }
  setFromQuaternion(e, f, n) {
    return rK.makeRotationFromQuaternion(e), this.setFromRotationMatrix(rK, f, n);
  }
  setFromVector3(e, f = this._order) {
    return this.set(e.x, e.y, e.z, f);
  }
  reorder(e) {
    return zK.setFromEuler(this), this.setFromQuaternion(zK, e);
  }
  equals(e) {
    return e._x === this._x && e._y === this._y && e._z === this._z && e._order === this._order;
  }
  fromArray(e) {
    return this._x = e[0], this._y = e[1], this._z = e[2], e[3] !== void 0 && (this._order = e[3]), this._onChangeCallback(), this;
  }
  toArray(e = [], f = 0) {
    return e[f] = this._x, e[f + 1] = this._y, e[f + 2] = this._z, e[f + 3] = this._order, e;
  }
  _onChange(e) {
    return this._onChangeCallback = e, this;
  }
  _onChangeCallback() {
  }
  *[Symbol.iterator]() {
    yield this._x, yield this._y, yield this._z, yield this._order;
  }
}
xz.DEFAULT_ORDER = "XYZ";
class PM {
  constructor() {
    this.mask = 1;
  }
  set(e) {
    this.mask = (1 << e | 0) >>> 0;
  }
  enable(e) {
    this.mask |= 1 << e | 0;
  }
  enableAll() {
    this.mask = -1;
  }
  toggle(e) {
    this.mask ^= 1 << e | 0;
  }
  disable(e) {
    this.mask &= ~(1 << e | 0);
  }
  disableAll() {
    this.mask = 0;
  }
  test(e) {
    return (this.mask & e.mask) !== 0;
  }
  isEnabled(e) {
    return (this.mask & (1 << e | 0)) !== 0;
  }
}
let vFt = 0;
const uK = new ht();
const e1 = new Mb();
const W9 = new cn();
const Z6 = new ht();
const Gs = new ht();
const rFt = new ht();
const zFt = new Mb();
const bK = new ht(1, 0, 0);
const aK = new ht(0, 1, 0);
const PK = new ht(0, 0, 1);
const sK = { type: "added" };
const uFt = { type: "removed" };
const f1 = { type: "childadded", child: null };
const sM = { type: "childremoved", child: null };
class zv extends Nb {
  constructor() {
    super(), this.isObject3D = true, Object.defineProperty(this, "id", { value: vFt++ }), this.uuid = jz(), this.name = "", this.type = "Object3D", this.parent = null, this.children = [], this.up = zv.DEFAULT_UP.clone();
    const e = new ht(), f = new xz(), n = new Mb(), v = new ht(1, 1, 1);
    function r() {
      n.setFromEuler(f, false);
    }
    function z() {
      f.setFromQuaternion(n, void 0, false);
    }
    f._onChange(r), n._onChange(z), Object.defineProperties(this, { position: { configurable: true, enumerable: true, value: e }, rotation: { configurable: true, enumerable: true, value: f }, quaternion: { configurable: true, enumerable: true, value: n }, scale: { configurable: true, enumerable: true, value: v }, modelViewMatrix: { value: new cn() }, normalMatrix: { value: new Tf() } }), this.matrix = new cn(), this.matrixWorld = new cn(), this.matrixAutoUpdate = zv.DEFAULT_MATRIX_AUTO_UPDATE, this.matrixWorldAutoUpdate = zv.DEFAULT_MATRIX_WORLD_AUTO_UPDATE, this.matrixWorldNeedsUpdate = false, this.layers = new PM(), this.visible = true, this.castShadow = false, this.receiveShadow = false, this.frustumCulled = true, this.renderOrder = 0, this.animations = [], this.customDepthMaterial = void 0, this.customDistanceMaterial = void 0, this.userData = {};
  }
  onBeforeShadow() {
  }
  onAfterShadow() {
  }
  onBeforeRender() {
  }
  onAfterRender() {
  }
  applyMatrix4(e) {
    this.matrixAutoUpdate && this.updateMatrix(), this.matrix.premultiply(e), this.matrix.decompose(this.position, this.quaternion, this.scale);
  }
  applyQuaternion(e) {
    return this.quaternion.premultiply(e), this;
  }
  setRotationFromAxisAngle(e, f) {
    this.quaternion.setFromAxisAngle(e, f);
  }
  setRotationFromEuler(e) {
    this.quaternion.setFromEuler(e, true);
  }
  setRotationFromMatrix(e) {
    this.quaternion.setFromRotationMatrix(e);
  }
  setRotationFromQuaternion(e) {
    this.quaternion.copy(e);
  }
  rotateOnAxis(e, f) {
    return e1.setFromAxisAngle(e, f), this.quaternion.multiply(e1), this;
  }
  rotateOnWorldAxis(e, f) {
    return e1.setFromAxisAngle(e, f), this.quaternion.premultiply(e1), this;
  }
  rotateX(e) {
    return this.rotateOnAxis(bK, e);
  }
  rotateY(e) {
    return this.rotateOnAxis(aK, e);
  }
  rotateZ(e) {
    return this.rotateOnAxis(PK, e);
  }
  translateOnAxis(e, f) {
    return uK.copy(e).applyQuaternion(this.quaternion), this.position.add(uK.multiplyScalar(f)), this;
  }
  translateX(e) {
    return this.translateOnAxis(bK, e);
  }
  translateY(e) {
    return this.translateOnAxis(aK, e);
  }
  translateZ(e) {
    return this.translateOnAxis(PK, e);
  }
  localToWorld(e) {
    return this.updateWorldMatrix(true, false), e.applyMatrix4(this.matrixWorld);
  }
  worldToLocal(e) {
    return this.updateWorldMatrix(true, false), e.applyMatrix4(W9.copy(this.matrixWorld).invert());
  }
  lookAt(e, f, n) {
    e.isVector3 ? Z6.copy(e) : Z6.set(e, f, n);
    const v = this.parent;
    this.updateWorldMatrix(true, false), Gs.setFromMatrixPosition(this.matrixWorld), this.isCamera || this.isLight ? W9.lookAt(Gs, Z6, this.up) : W9.lookAt(Z6, Gs, this.up), this.quaternion.setFromRotationMatrix(W9), v && (W9.extractRotation(v.matrixWorld), e1.setFromRotationMatrix(W9), this.quaternion.premultiply(e1.invert()));
  }
  add(e) {
    if (arguments.length > 1) {
      for (let f = 0; f < arguments.length; f++) this.add(arguments[f]);
      return this;
    }
    return e === this ? (console.error("THREE.Object3D.add: object can't be added as a child of itself.", e), this) : (e && e.isObject3D ? (e.removeFromParent(), e.parent = this, this.children.push(e), e.dispatchEvent(sK), f1.child = e, this.dispatchEvent(f1), f1.child = null) : console.error("THREE.Object3D.add: object not an instance of THREE.Object3D.", e), this);
  }
  remove(e) {
    if (arguments.length > 1) {
      for (let n = 0; n < arguments.length; n++) this.remove(arguments[n]);
      return this;
    }
    const f = this.children.indexOf(e);
    return f !== -1 && (e.parent = null, this.children.splice(f, 1), e.dispatchEvent(uFt), sM.child = e, this.dispatchEvent(sM), sM.child = null), this;
  }
  removeFromParent() {
    const e = this.parent;
    return e !== null && e.remove(this), this;
  }
  clear() {
    return this.remove(...this.children);
  }
  attach(e) {
    return this.updateWorldMatrix(true, false), W9.copy(this.matrixWorld).invert(), e.parent !== null && (e.parent.updateWorldMatrix(true, false), W9.multiply(e.parent.matrixWorld)), e.applyMatrix4(W9), e.removeFromParent(), e.parent = this, this.children.push(e), e.updateWorldMatrix(false, true), e.dispatchEvent(sK), f1.child = e, this.dispatchEvent(f1), f1.child = null, this;
  }
  getObjectById(e) {
    return this.getObjectByProperty("id", e);
  }
  getObjectByName(e) {
    return this.getObjectByProperty("name", e);
  }
  getObjectByProperty(e, f) {
    if (this[e] === f) return this;
    for (let n = 0, v = this.children.length; n < v; n++) {
      const z = this.children[n].getObjectByProperty(e, f);
      if (z !== void 0) return z;
    }
  }
  getObjectsByProperty(e, f, n = []) {
    this[e] === f && n.push(this);
    const v = this.children;
    for (let r = 0, z = v.length; r < z; r++) v[r].getObjectsByProperty(e, f, n);
    return n;
  }
  getWorldPosition(e) {
    return this.updateWorldMatrix(true, false), e.setFromMatrixPosition(this.matrixWorld);
  }
  getWorldQuaternion(e) {
    return this.updateWorldMatrix(true, false), this.matrixWorld.decompose(Gs, e, rFt), e;
  }
  getWorldScale(e) {
    return this.updateWorldMatrix(true, false), this.matrixWorld.decompose(Gs, zFt, e), e;
  }
  getWorldDirection(e) {
    this.updateWorldMatrix(true, false);
    const f = this.matrixWorld.elements;
    return e.set(f[8], f[9], f[10]).normalize();
  }
  raycast() {
  }
  traverse(e) {
    e(this);
    const f = this.children;
    for (let n = 0, v = f.length; n < v; n++) f[n].traverse(e);
  }
  traverseVisible(e) {
    if (this.visible === false) return;
    e(this);
    const f = this.children;
    for (let n = 0, v = f.length; n < v; n++) f[n].traverseVisible(e);
  }
  traverseAncestors(e) {
    const f = this.parent;
    f !== null && (e(f), f.traverseAncestors(e));
  }
  updateMatrix() {
    this.matrix.compose(this.position, this.quaternion, this.scale), this.matrixWorldNeedsUpdate = true;
  }
  updateMatrixWorld(e) {
    this.matrixAutoUpdate && this.updateMatrix(), (this.matrixWorldNeedsUpdate || e) && (this.matrixWorldAutoUpdate === true && (this.parent === null ? this.matrixWorld.copy(this.matrix) : this.matrixWorld.multiplyMatrices(this.parent.matrixWorld, this.matrix)), this.matrixWorldNeedsUpdate = false, e = true);
    const f = this.children;
    for (let n = 0, v = f.length; n < v; n++) f[n].updateMatrixWorld(e);
  }
  updateWorldMatrix(e, f) {
    const n = this.parent;
    if (e === true && n !== null && n.updateWorldMatrix(true, false), this.matrixAutoUpdate && this.updateMatrix(), this.matrixWorldAutoUpdate === true && (this.parent === null ? this.matrixWorld.copy(this.matrix) : this.matrixWorld.multiplyMatrices(this.parent.matrixWorld, this.matrix)), f === true) {
      const v = this.children;
      for (let r = 0, z = v.length; r < z; r++) v[r].updateWorldMatrix(false, true);
    }
  }
  toJSON(e) {
    const f = e === void 0 || typeof e == "string", n = {};
    f && (e = { geometries: {}, materials: {}, textures: {}, images: {}, shapes: {}, skeletons: {}, animations: {}, nodes: {} }, n.metadata = { version: 4.7, type: "Object", generator: "Object3D.toJSON" });
    const v = {};
    v.uuid = this.uuid, v.type = this.type, this.name !== "" && (v.name = this.name), this.castShadow === true && (v.castShadow = true), this.receiveShadow === true && (v.receiveShadow = true), this.visible === false && (v.visible = false), this.frustumCulled === false && (v.frustumCulled = false), this.renderOrder !== 0 && (v.renderOrder = this.renderOrder), Object.keys(this.userData).length > 0 && (v.userData = this.userData), v.layers = this.layers.mask, v.matrix = this.matrix.toArray(), v.up = this.up.toArray(), this.matrixAutoUpdate === false && (v.matrixAutoUpdate = false), this.isInstancedMesh && (v.type = "InstancedMesh", v.count = this.count, v.instanceMatrix = this.instanceMatrix.toJSON(), this.instanceColor !== null && (v.instanceColor = this.instanceColor.toJSON())), this.isBatchedMesh && (v.type = "BatchedMesh", v.perObjectFrustumCulled = this.perObjectFrustumCulled, v.sortObjects = this.sortObjects, v.drawRanges = this._drawRanges, v.reservedRanges = this._reservedRanges, v.geometryInfo = this._geometryInfo.map((u) => ({ ...u, boundingBox: u.boundingBox ? u.boundingBox.toJSON() : void 0, boundingSphere: u.boundingSphere ? u.boundingSphere.toJSON() : void 0 })), v.instanceInfo = this._instanceInfo.map((u) => ({ ...u })), v.availableInstanceIds = this._availableInstanceIds.slice(), v.availableGeometryIds = this._availableGeometryIds.slice(), v.nextIndexStart = this._nextIndexStart, v.nextVertexStart = this._nextVertexStart, v.geometryCount = this._geometryCount, v.maxInstanceCount = this._maxInstanceCount, v.maxVertexCount = this._maxVertexCount, v.maxIndexCount = this._maxIndexCount, v.geometryInitialized = this._geometryInitialized, v.matricesTexture = this._matricesTexture.toJSON(e), v.indirectTexture = this._indirectTexture.toJSON(e), this._colorsTexture !== null && (v.colorsTexture = this._colorsTexture.toJSON(e)), this.boundingSphere !== null && (v.boundingSphere = this.boundingSphere.toJSON()), this.boundingBox !== null && (v.boundingBox = this.boundingBox.toJSON()));
    function r(u, b) {
      return u[b.uuid] === void 0 && (u[b.uuid] = b.toJSON(e)), b.uuid;
    }
    if (this.isScene) this.background && (this.background.isColor ? v.background = this.background.toJSON() : this.background.isTexture && (v.background = this.background.toJSON(e).uuid)), this.environment && this.environment.isTexture && this.environment.isRenderTargetTexture !== true && (v.environment = this.environment.toJSON(e).uuid);
    else if (this.isMesh || this.isLine || this.isPoints) {
      v.geometry = r(e.geometries, this.geometry);
      const u = this.geometry.parameters;
      if (u !== void 0 && u.shapes !== void 0) {
        const b = u.shapes;
        if (Array.isArray(b)) for (let a = 0, P = b.length; a < P; a++) {
          const s = b[a];
          r(e.shapes, s);
        }
        else r(e.shapes, b);
      }
    }
    if (this.isSkinnedMesh && (v.bindMode = this.bindMode, v.bindMatrix = this.bindMatrix.toArray(), this.skeleton !== void 0 && (r(e.skeletons, this.skeleton), v.skeleton = this.skeleton.uuid)), this.material !== void 0) if (Array.isArray(this.material)) {
      const u = [];
      for (let b = 0, a = this.material.length; b < a; b++) u.push(r(e.materials, this.material[b]));
      v.material = u;
    } else v.material = r(e.materials, this.material);
    if (this.children.length > 0) {
      v.children = [];
      for (let u = 0; u < this.children.length; u++) v.children.push(this.children[u].toJSON(e).object);
    }
    if (this.animations.length > 0) {
      v.animations = [];
      for (let u = 0; u < this.animations.length; u++) {
        const b = this.animations[u];
        v.animations.push(r(e.animations, b));
      }
    }
    if (f) {
      const u = z(e.geometries), b = z(e.materials), a = z(e.textures), P = z(e.images), s = z(e.shapes), d = z(e.skeletons), c = z(e.animations), l = z(e.nodes);
      u.length > 0 && (n.geometries = u), b.length > 0 && (n.materials = b), a.length > 0 && (n.textures = a), P.length > 0 && (n.images = P), s.length > 0 && (n.shapes = s), d.length > 0 && (n.skeletons = d), c.length > 0 && (n.animations = c), l.length > 0 && (n.nodes = l);
    }
    return n.object = v, n;
    function z(u) {
      const b = [];
      for (const a in u) {
        const P = u[a];
        delete P.metadata, b.push(P);
      }
      return b;
    }
  }
  clone(e) {
    return new this.constructor().copy(this, e);
  }
  copy(e, f = true) {
    if (this.name = e.name, this.up.copy(e.up), this.position.copy(e.position), this.rotation.order = e.rotation.order, this.quaternion.copy(e.quaternion), this.scale.copy(e.scale), this.matrix.copy(e.matrix), this.matrixWorld.copy(e.matrixWorld), this.matrixAutoUpdate = e.matrixAutoUpdate, this.matrixWorldAutoUpdate = e.matrixWorldAutoUpdate, this.matrixWorldNeedsUpdate = e.matrixWorldNeedsUpdate, this.layers.mask = e.layers.mask, this.visible = e.visible, this.castShadow = e.castShadow, this.receiveShadow = e.receiveShadow, this.frustumCulled = e.frustumCulled, this.renderOrder = e.renderOrder, this.animations = e.animations.slice(), this.userData = JSON.parse(JSON.stringify(e.userData)), f === true) for (let n = 0; n < e.children.length; n++) {
      const v = e.children[n];
      this.add(v.clone());
    }
    return this;
  }
}
zv.DEFAULT_UP = new ht(0, 1, 0), zv.DEFAULT_MATRIX_AUTO_UPDATE = true, zv.DEFAULT_MATRIX_WORLD_AUTO_UPDATE = true;
const j7 = new ht();
const y9 = new ht();
const dM = new ht();
const Z9 = new ht();
const n1 = new ht();
const v1 = new ht();
const dK = new ht();
const cM = new ht();
const lM = new ht();
const XM = new ht();
const WM = new Un();
const yM = new Un();
const ZM = new Un();
class fr {
  constructor(e = new ht(), f = new ht(), n = new ht()) {
    this.a = e, this.b = f, this.c = n;
  }
  static getNormal(e, f, n, v) {
    v.subVectors(n, f), j7.subVectors(e, f), v.cross(j7);
    const r = v.lengthSq();
    return r > 0 ? v.multiplyScalar(1 / Math.sqrt(r)) : v.set(0, 0, 0);
  }
  static getBarycoord(e, f, n, v, r) {
    j7.subVectors(v, f), y9.subVectors(n, f), dM.subVectors(e, f);
    const z = j7.dot(j7), u = j7.dot(y9), b = j7.dot(dM), a = y9.dot(y9), P = y9.dot(dM), s = z * a - u * u;
    if (s === 0) return r.set(0, 0, 0), null;
    const d = 1 / s, c = (a * b - u * P) * d, l = (z * P - u * b) * d;
    return r.set(1 - c - l, l, c);
  }
  static containsPoint(e, f, n, v) {
    return this.getBarycoord(e, f, n, v, Z9) === null ? false : Z9.x >= 0 && Z9.y >= 0 && Z9.x + Z9.y <= 1;
  }
  static getInterpolation(e, f, n, v, r, z, u, b) {
    return this.getBarycoord(e, f, n, v, Z9) === null ? (b.x = 0, b.y = 0, "z" in b && (b.z = 0), "w" in b && (b.w = 0), null) : (b.setScalar(0), b.addScaledVector(r, Z9.x), b.addScaledVector(z, Z9.y), b.addScaledVector(u, Z9.z), b);
  }
  static getInterpolatedAttribute(e, f, n, v, r, z) {
    return WM.setScalar(0), yM.setScalar(0), ZM.setScalar(0), WM.fromBufferAttribute(e, f), yM.fromBufferAttribute(e, n), ZM.fromBufferAttribute(e, v), z.setScalar(0), z.addScaledVector(WM, r.x), z.addScaledVector(yM, r.y), z.addScaledVector(ZM, r.z), z;
  }
  static isFrontFacing(e, f, n, v) {
    return j7.subVectors(n, f), y9.subVectors(e, f), j7.cross(y9).dot(v) < 0;
  }
  set(e, f, n) {
    return this.a.copy(e), this.b.copy(f), this.c.copy(n), this;
  }
  setFromPointsAndIndices(e, f, n, v) {
    return this.a.copy(e[f]), this.b.copy(e[n]), this.c.copy(e[v]), this;
  }
  setFromAttributeAndIndices(e, f, n, v) {
    return this.a.fromBufferAttribute(e, f), this.b.fromBufferAttribute(e, n), this.c.fromBufferAttribute(e, v), this;
  }
  clone() {
    return new this.constructor().copy(this);
  }
  copy(e) {
    return this.a.copy(e.a), this.b.copy(e.b), this.c.copy(e.c), this;
  }
  getArea() {
    return j7.subVectors(this.c, this.b), y9.subVectors(this.a, this.b), j7.cross(y9).length() * 0.5;
  }
  getMidpoint(e) {
    return e.addVectors(this.a, this.b).add(this.c).multiplyScalar(1 / 3);
  }
  getNormal(e) {
    return fr.getNormal(this.a, this.b, this.c, e);
  }
  getPlane(e) {
    return e.setFromCoplanarPoints(this.a, this.b, this.c);
  }
  getBarycoord(e, f) {
    return fr.getBarycoord(e, this.a, this.b, this.c, f);
  }
  getInterpolation(e, f, n, v, r) {
    return fr.getInterpolation(e, this.a, this.b, this.c, f, n, v, r);
  }
  containsPoint(e) {
    return fr.containsPoint(e, this.a, this.b, this.c);
  }
  isFrontFacing(e) {
    return fr.isFrontFacing(this.a, this.b, this.c, e);
  }
  intersectsBox(e) {
    return e.intersectsTriangle(this);
  }
  closestPointToPoint(e, f) {
    const n = this.a, v = this.b, r = this.c;
    let z, u;
    n1.subVectors(v, n), v1.subVectors(r, n), cM.subVectors(e, n);
    const b = n1.dot(cM), a = v1.dot(cM);
    if (b <= 0 && a <= 0) return f.copy(n);
    lM.subVectors(e, v);
    const P = n1.dot(lM), s = v1.dot(lM);
    if (P >= 0 && s <= P) return f.copy(v);
    const d = b * s - P * a;
    if (d <= 0 && b >= 0 && P <= 0) return z = b / (b - P), f.copy(n).addScaledVector(n1, z);
    XM.subVectors(e, r);
    const c = n1.dot(XM), l = v1.dot(XM);
    if (l >= 0 && c <= l) return f.copy(r);
    const W = c * a - b * l;
    if (W <= 0 && a >= 0 && l <= 0) return u = a / (a - l), f.copy(n).addScaledVector(v1, u);
    const X = P * l - c * s;
    if (X <= 0 && s - P >= 0 && c - l >= 0) return dK.subVectors(r, v), u = (s - P) / (s - P + (c - l)), f.copy(v).addScaledVector(dK, u);
    const y = 1 / (X + W + d);
    return z = W * y, u = d * y, f.copy(n).addScaledVector(n1, z).addScaledVector(v1, u);
  }
  equals(e) {
    return e.a.equals(this.a) && e.b.equals(this.b) && e.c.equals(this.c);
  }
}
const cK = { aliceblue: 15792383, antiquewhite: 16444375, aqua: 65535, aquamarine: 8388564, azure: 15794175, beige: 16119260, bisque: 16770244, black: 0, blanchedalmond: 16772045, blue: 255, blueviolet: 9055202, brown: 10824234, burlywood: 14596231, cadetblue: 6266528, chartreuse: 8388352, chocolate: 13789470, coral: 16744272, cornflowerblue: 6591981, cornsilk: 16775388, crimson: 14423100, cyan: 65535, darkblue: 139, darkcyan: 35723, darkgoldenrod: 12092939, darkgray: 11119017, darkgreen: 25600, darkgrey: 11119017, darkkhaki: 12433259, darkmagenta: 9109643, darkolivegreen: 5597999, darkorange: 16747520, darkorchid: 10040012, darkred: 9109504, darksalmon: 15308410, darkseagreen: 9419919, darkslateblue: 4734347, darkslategray: 3100495, darkslategrey: 3100495, darkturquoise: 52945, darkviolet: 9699539, deeppink: 16716947, deepskyblue: 49151, dimgray: 6908265, dimgrey: 6908265, dodgerblue: 2003199, firebrick: 11674146, floralwhite: 16775920, forestgreen: 2263842, fuchsia: 16711935, gainsboro: 14474460, ghostwhite: 16316671, gold: 16766720, goldenrod: 14329120, gray: 8421504, green: 32768, greenyellow: 11403055, grey: 8421504, honeydew: 15794160, hotpink: 16738740, indianred: 13458524, indigo: 4915330, ivory: 16777200, khaki: 15787660, lavender: 15132410, lavenderblush: 16773365, lawngreen: 8190976, lemonchiffon: 16775885, lightblue: 11393254, lightcoral: 15761536, lightcyan: 14745599, lightgoldenrodyellow: 16448210, lightgray: 13882323, lightgreen: 9498256, lightgrey: 13882323, lightpink: 16758465, lightsalmon: 16752762, lightseagreen: 2142890, lightskyblue: 8900346, lightslategray: 7833753, lightslategrey: 7833753, lightsteelblue: 11584734, lightyellow: 16777184, lime: 65280, limegreen: 3329330, linen: 16445670, magenta: 16711935, maroon: 8388608, mediumaquamarine: 6737322, mediumblue: 205, mediumorchid: 12211667, mediumpurple: 9662683, mediumseagreen: 3978097, mediumslateblue: 8087790, mediumspringgreen: 64154, mediumturquoise: 4772300, mediumvioletred: 13047173, midnightblue: 1644912, mintcream: 16121850, mistyrose: 16770273, moccasin: 16770229, navajowhite: 16768685, navy: 128, oldlace: 16643558, olive: 8421376, olivedrab: 7048739, orange: 16753920, orangered: 16729344, orchid: 14315734, palegoldenrod: 15657130, palegreen: 10025880, paleturquoise: 11529966, palevioletred: 14381203, papayawhip: 16773077, peachpuff: 16767673, peru: 13468991, pink: 16761035, plum: 14524637, powderblue: 11591910, purple: 8388736, rebeccapurple: 6697881, red: 16711680, rosybrown: 12357519, royalblue: 4286945, saddlebrown: 9127187, salmon: 16416882, sandybrown: 16032864, seagreen: 3050327, seashell: 16774638, sienna: 10506797, silver: 12632256, skyblue: 8900331, slateblue: 6970061, slategray: 7372944, slategrey: 7372944, snow: 16775930, springgreen: 65407, steelblue: 4620980, tan: 13808780, teal: 32896, thistle: 14204888, tomato: 16737095, turquoise: 4251856, violet: 15631086, wheat: 16113331, white: 16777215, whitesmoke: 16119285, yellow: 16776960, yellowgreen: 10145074 };
const V5 = { h: 0, s: 0, l: 0 };
const m6 = { h: 0, s: 0, l: 0 };
function mM(t, e, f) {
  return f < 0 && (f += 1), f > 1 && (f -= 1), f < 1 / 6 ? t + (e - t) * 6 * f : f < 1 / 2 ? e : f < 2 / 3 ? t + (e - t) * 6 * (2 / 3 - f) : t;
}
class Nf {
  constructor(e, f, n) {
    return this.isColor = true, this.r = 1, this.g = 1, this.b = 1, this.set(e, f, n);
  }
  set(e, f, n) {
    if (f === void 0 && n === void 0) {
      const v = e;
      v && v.isColor ? this.copy(v) : typeof v == "number" ? this.setHex(v) : typeof v == "string" && this.setStyle(v);
    } else this.setRGB(e, f, n);
    return this;
  }
  setScalar(e) {
    return this.r = e, this.g = e, this.b = e, this;
  }
  setHex(e, f = Qv) {
    return e = Math.floor(e), this.r = (e >> 16 & 255) / 255, this.g = (e >> 8 & 255) / 255, this.b = (e & 255) / 255, Uf.colorSpaceToWorking(this, f), this;
  }
  setRGB(e, f, n, v = Uf.workingColorSpace) {
    return this.r = e, this.g = f, this.b = n, Uf.colorSpaceToWorking(this, v), this;
  }
  setHSL(e, f, n, v = Uf.workingColorSpace) {
    if (e = $N(e, 1), f = pf(f, 0, 1), n = pf(n, 0, 1), f === 0) this.r = this.g = this.b = n;
    else {
      const r = n <= 0.5 ? n * (1 + f) : n + f - n * f, z = 2 * n - r;
      this.r = mM(z, r, e + 1 / 3), this.g = mM(z, r, e), this.b = mM(z, r, e - 1 / 3);
    }
    return Uf.colorSpaceToWorking(this, v), this;
  }
  setStyle(e, f = Qv) {
    function n(r) {
      r !== void 0 && parseFloat(r) < 1 && console.warn("THREE.Color: Alpha component of " + e + " will be ignored.");
    }
    let v;
    if (v = /^(\w+)\(([^\)]*)\)/.exec(e)) {
      let r;
      const z = v[1], u = v[2];
      switch (z) {
        case "rgb":
        case "rgba":
          if (r = /^\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*(\d*\.?\d+)\s*)?$/.exec(u)) return n(r[4]), this.setRGB(Math.min(255, parseInt(r[1], 10)) / 255, Math.min(255, parseInt(r[2], 10)) / 255, Math.min(255, parseInt(r[3], 10)) / 255, f);
          if (r = /^\s*(\d+)\%\s*,\s*(\d+)\%\s*,\s*(\d+)\%\s*(?:,\s*(\d*\.?\d+)\s*)?$/.exec(u)) return n(r[4]), this.setRGB(Math.min(100, parseInt(r[1], 10)) / 100, Math.min(100, parseInt(r[2], 10)) / 100, Math.min(100, parseInt(r[3], 10)) / 100, f);
          break;
        case "hsl":
        case "hsla":
          if (r = /^\s*(\d*\.?\d+)\s*,\s*(\d*\.?\d+)\%\s*,\s*(\d*\.?\d+)\%\s*(?:,\s*(\d*\.?\d+)\s*)?$/.exec(u)) return n(r[4]), this.setHSL(parseFloat(r[1]) / 360, parseFloat(r[2]) / 100, parseFloat(r[3]) / 100, f);
          break;
        default:
          console.warn("THREE.Color: Unknown color model " + e);
      }
    } else if (v = /^\#([A-Fa-f\d]+)$/.exec(e)) {
      const r = v[1], z = r.length;
      if (z === 3) return this.setRGB(parseInt(r.charAt(0), 16) / 15, parseInt(r.charAt(1), 16) / 15, parseInt(r.charAt(2), 16) / 15, f);
      if (z === 6) return this.setHex(parseInt(r, 16), f);
      console.warn("THREE.Color: Invalid hex color " + e);
    } else if (e && e.length > 0) return this.setColorName(e, f);
    return this;
  }
  setColorName(e, f = Qv) {
    const n = cK[e.toLowerCase()];
    return n !== void 0 ? this.setHex(n, f) : console.warn("THREE.Color: Unknown color " + e), this;
  }
  clone() {
    return new this.constructor(this.r, this.g, this.b);
  }
  copy(e) {
    return this.r = e.r, this.g = e.g, this.b = e.b, this;
  }
  copySRGBToLinear(e) {
    return this.r = d9(e.r), this.g = d9(e.g), this.b = d9(e.b), this;
  }
  copyLinearToSRGB(e) {
    return this.r = Ea(e.r), this.g = Ea(e.g), this.b = Ea(e.b), this;
  }
  convertSRGBToLinear() {
    return this.copySRGBToLinear(this), this;
  }
  convertLinearToSRGB() {
    return this.copyLinearToSRGB(this), this;
  }
  getHex(e = Qv) {
    return Uf.workingToColorSpace($v.copy(this), e), Math.round(pf($v.r * 255, 0, 255)) * 65536 + Math.round(pf($v.g * 255, 0, 255)) * 256 + Math.round(pf($v.b * 255, 0, 255));
  }
  getHexString(e = Qv) {
    return ("000000" + this.getHex(e).toString(16)).slice(-6);
  }
  getHSL(e, f = Uf.workingColorSpace) {
    Uf.workingToColorSpace($v.copy(this), f);
    const n = $v.r, v = $v.g, r = $v.b, z = Math.max(n, v, r), u = Math.min(n, v, r);
    let b, a;
    const P = (u + z) / 2;
    if (u === z) b = 0, a = 0;
    else {
      const s = z - u;
      switch (a = P <= 0.5 ? s / (z + u) : s / (2 - z - u), z) {
        case n:
          b = (v - r) / s + (v < r ? 6 : 0);
          break;
        case v:
          b = (r - n) / s + 2;
          break;
        case r:
          b = (n - v) / s + 4;
          break;
      }
      b /= 6;
    }
    return e.h = b, e.s = a, e.l = P, e;
  }
  getRGB(e, f = Uf.workingColorSpace) {
    return Uf.workingToColorSpace($v.copy(this), f), e.r = $v.r, e.g = $v.g, e.b = $v.b, e;
  }
  getStyle(e = Qv) {
    Uf.workingToColorSpace($v.copy(this), e);
    const f = $v.r, n = $v.g, v = $v.b;
    return e !== Qv ? `color(${e} ${f.toFixed(3)} ${n.toFixed(3)} ${v.toFixed(3)})` : `rgb(${Math.round(f * 255)},${Math.round(n * 255)},${Math.round(v * 255)})`;
  }
  offsetHSL(e, f, n) {
    return this.getHSL(V5), this.setHSL(V5.h + e, V5.s + f, V5.l + n);
  }
  add(e) {
    return this.r += e.r, this.g += e.g, this.b += e.b, this;
  }
  addColors(e, f) {
    return this.r = e.r + f.r, this.g = e.g + f.g, this.b = e.b + f.b, this;
  }
  addScalar(e) {
    return this.r += e, this.g += e, this.b += e, this;
  }
  sub(e) {
    return this.r = Math.max(0, this.r - e.r), this.g = Math.max(0, this.g - e.g), this.b = Math.max(0, this.b - e.b), this;
  }
  multiply(e) {
    return this.r *= e.r, this.g *= e.g, this.b *= e.b, this;
  }
  multiplyScalar(e) {
    return this.r *= e, this.g *= e, this.b *= e, this;
  }
  lerp(e, f) {
    return this.r += (e.r - this.r) * f, this.g += (e.g - this.g) * f, this.b += (e.b - this.b) * f, this;
  }
  lerpColors(e, f, n) {
    return this.r = e.r + (f.r - e.r) * n, this.g = e.g + (f.g - e.g) * n, this.b = e.b + (f.b - e.b) * n, this;
  }
  lerpHSL(e, f) {
    this.getHSL(V5), e.getHSL(m6);
    const n = Ts(V5.h, m6.h, f), v = Ts(V5.s, m6.s, f), r = Ts(V5.l, m6.l, f);
    return this.setHSL(n, v, r), this;
  }
  setFromVector3(e) {
    return this.r = e.x, this.g = e.y, this.b = e.z, this;
  }
  applyMatrix3(e) {
    const f = this.r, n = this.g, v = this.b, r = e.elements;
    return this.r = r[0] * f + r[3] * n + r[6] * v, this.g = r[1] * f + r[4] * n + r[7] * v, this.b = r[2] * f + r[5] * n + r[8] * v, this;
  }
  equals(e) {
    return e.r === this.r && e.g === this.g && e.b === this.b;
  }
  fromArray(e, f = 0) {
    return this.r = e[f], this.g = e[f + 1], this.b = e[f + 2], this;
  }
  toArray(e = [], f = 0) {
    return e[f] = this.r, e[f + 1] = this.g, e[f + 2] = this.b, e;
  }
  fromBufferAttribute(e, f) {
    return this.r = e.getX(f), this.g = e.getY(f), this.b = e.getZ(f), this;
  }
  toJSON() {
    return this.getHex();
  }
  *[Symbol.iterator]() {
    yield this.r, yield this.g, yield this.b;
  }
}
const $v = new Nf();
Nf.NAMES = cK;
let bFt = 0;
class ob extends Nb {
  constructor() {
    super(), this.isMaterial = true, Object.defineProperty(this, "id", { value: bFt++ }), this.uuid = jz(), this.name = "", this.type = "Material", this.blending = ka, this.side = M5, this.vertexColors = false, this.opacity = 1, this.transparent = false, this.alphaHash = false, this.blendSrc = rN, this.blendDst = zN, this.blendEquation = xb, this.blendSrcAlpha = null, this.blendDstAlpha = null, this.blendEquationAlpha = null, this.blendColor = new Nf(0, 0, 0), this.blendAlpha = 0, this.depthFunc = Ja, this.depthTest = true, this.depthWrite = true, this.stencilWriteMask = 255, this.stencilFunc = CI, this.stencilRef = 0, this.stencilFuncMask = 255, this.stencilFail = Ia, this.stencilZFail = Ia, this.stencilZPass = Ia, this.stencilWrite = false, this.clippingPlanes = null, this.clipIntersection = false, this.clipShadows = false, this.shadowSide = null, this.colorWrite = true, this.precision = null, this.polygonOffset = false, this.polygonOffsetFactor = 0, this.polygonOffsetUnits = 0, this.dithering = false, this.alphaToCoverage = false, this.premultipliedAlpha = false, this.forceSinglePass = false, this.allowOverride = true, this.visible = true, this.toneMapped = true, this.userData = {}, this.version = 0, this._alphaTest = 0;
  }
  get alphaTest() {
    return this._alphaTest;
  }
  set alphaTest(e) {
    this._alphaTest > 0 != e > 0 && this.version++, this._alphaTest = e;
  }
  onBeforeRender() {
  }
  onBeforeCompile() {
  }
  customProgramCacheKey() {
    return this.onBeforeCompile.toString();
  }
  setValues(e) {
    if (e !== void 0) for (const f in e) {
      const n = e[f];
      if (n === void 0) {
        console.warn(`THREE.Material: parameter '${f}' has value of undefined.`);
        continue;
      }
      const v = this[f];
      if (v === void 0) {
        console.warn(`THREE.Material: '${f}' is not a property of THREE.${this.type}.`);
        continue;
      }
      v && v.isColor ? v.set(n) : v && v.isVector3 && n && n.isVector3 ? v.copy(n) : this[f] = n;
    }
  }
  toJSON(e) {
    const f = e === void 0 || typeof e == "string";
    f && (e = { textures: {}, images: {} });
    const n = { metadata: { version: 4.7, type: "Material", generator: "Material.toJSON" } };
    n.uuid = this.uuid, n.type = this.type, this.name !== "" && (n.name = this.name), this.color && this.color.isColor && (n.color = this.color.getHex()), this.roughness !== void 0 && (n.roughness = this.roughness), this.metalness !== void 0 && (n.metalness = this.metalness), this.sheen !== void 0 && (n.sheen = this.sheen), this.sheenColor && this.sheenColor.isColor && (n.sheenColor = this.sheenColor.getHex()), this.sheenRoughness !== void 0 && (n.sheenRoughness = this.sheenRoughness), this.emissive && this.emissive.isColor && (n.emissive = this.emissive.getHex()), this.emissiveIntensity !== void 0 && this.emissiveIntensity !== 1 && (n.emissiveIntensity = this.emissiveIntensity), this.specular && this.specular.isColor && (n.specular = this.specular.getHex()), this.specularIntensity !== void 0 && (n.specularIntensity = this.specularIntensity), this.specularColor && this.specularColor.isColor && (n.specularColor = this.specularColor.getHex()), this.shininess !== void 0 && (n.shininess = this.shininess), this.clearcoat !== void 0 && (n.clearcoat = this.clearcoat), this.clearcoatRoughness !== void 0 && (n.clearcoatRoughness = this.clearcoatRoughness), this.clearcoatMap && this.clearcoatMap.isTexture && (n.clearcoatMap = this.clearcoatMap.toJSON(e).uuid), this.clearcoatRoughnessMap && this.clearcoatRoughnessMap.isTexture && (n.clearcoatRoughnessMap = this.clearcoatRoughnessMap.toJSON(e).uuid), this.clearcoatNormalMap && this.clearcoatNormalMap.isTexture && (n.clearcoatNormalMap = this.clearcoatNormalMap.toJSON(e).uuid, n.clearcoatNormalScale = this.clearcoatNormalScale.toArray()), this.sheenColorMap && this.sheenColorMap.isTexture && (n.sheenColorMap = this.sheenColorMap.toJSON(e).uuid), this.sheenRoughnessMap && this.sheenRoughnessMap.isTexture && (n.sheenRoughnessMap = this.sheenRoughnessMap.toJSON(e).uuid), this.dispersion !== void 0 && (n.dispersion = this.dispersion), this.iridescence !== void 0 && (n.iridescence = this.iridescence), this.iridescenceIOR !== void 0 && (n.iridescenceIOR = this.iridescenceIOR), this.iridescenceThicknessRange !== void 0 && (n.iridescenceThicknessRange = this.iridescenceThicknessRange), this.iridescenceMap && this.iridescenceMap.isTexture && (n.iridescenceMap = this.iridescenceMap.toJSON(e).uuid), this.iridescenceThicknessMap && this.iridescenceThicknessMap.isTexture && (n.iridescenceThicknessMap = this.iridescenceThicknessMap.toJSON(e).uuid), this.anisotropy !== void 0 && (n.anisotropy = this.anisotropy), this.anisotropyRotation !== void 0 && (n.anisotropyRotation = this.anisotropyRotation), this.anisotropyMap && this.anisotropyMap.isTexture && (n.anisotropyMap = this.anisotropyMap.toJSON(e).uuid), this.map && this.map.isTexture && (n.map = this.map.toJSON(e).uuid), this.matcap && this.matcap.isTexture && (n.matcap = this.matcap.toJSON(e).uuid), this.alphaMap && this.alphaMap.isTexture && (n.alphaMap = this.alphaMap.toJSON(e).uuid), this.lightMap && this.lightMap.isTexture && (n.lightMap = this.lightMap.toJSON(e).uuid, n.lightMapIntensity = this.lightMapIntensity), this.aoMap && this.aoMap.isTexture && (n.aoMap = this.aoMap.toJSON(e).uuid, n.aoMapIntensity = this.aoMapIntensity), this.bumpMap && this.bumpMap.isTexture && (n.bumpMap = this.bumpMap.toJSON(e).uuid, n.bumpScale = this.bumpScale), this.normalMap && this.normalMap.isTexture && (n.normalMap = this.normalMap.toJSON(e).uuid, n.normalMapType = this.normalMapType, n.normalScale = this.normalScale.toArray()), this.displacementMap && this.displacementMap.isTexture && (n.displacementMap = this.displacementMap.toJSON(e).uuid, n.displacementScale = this.displacementScale, n.displacementBias = this.displacementBias), this.roughnessMap && this.roughnessMap.isTexture && (n.roughnessMap = this.roughnessMap.toJSON(e).uuid), this.metalnessMap && this.metalnessMap.isTexture && (n.metalnessMap = this.metalnessMap.toJSON(e).uuid), this.emissiveMap && this.emissiveMap.isTexture && (n.emissiveMap = this.emissiveMap.toJSON(e).uuid), this.specularMap && this.specularMap.isTexture && (n.specularMap = this.specularMap.toJSON(e).uuid), this.specularIntensityMap && this.specularIntensityMap.isTexture && (n.specularIntensityMap = this.specularIntensityMap.toJSON(e).uuid), this.specularColorMap && this.specularColorMap.isTexture && (n.specularColorMap = this.specularColorMap.toJSON(e).uuid), this.envMap && this.envMap.isTexture && (n.envMap = this.envMap.toJSON(e).uuid, this.combine !== void 0 && (n.combine = this.combine)), this.envMapRotation !== void 0 && (n.envMapRotation = this.envMapRotation.toArray()), this.envMapIntensity !== void 0 && (n.envMapIntensity = this.envMapIntensity), this.reflectivity !== void 0 && (n.reflectivity = this.reflectivity), this.refractionRatio !== void 0 && (n.refractionRatio = this.refractionRatio), this.gradientMap && this.gradientMap.isTexture && (n.gradientMap = this.gradientMap.toJSON(e).uuid), this.transmission !== void 0 && (n.transmission = this.transmission), this.transmissionMap && this.transmissionMap.isTexture && (n.transmissionMap = this.transmissionMap.toJSON(e).uuid), this.thickness !== void 0 && (n.thickness = this.thickness), this.thicknessMap && this.thicknessMap.isTexture && (n.thicknessMap = this.thicknessMap.toJSON(e).uuid), this.attenuationDistance !== void 0 && this.attenuationDistance !== 1 / 0 && (n.attenuationDistance = this.attenuationDistance), this.attenuationColor !== void 0 && (n.attenuationColor = this.attenuationColor.getHex()), this.size !== void 0 && (n.size = this.size), this.shadowSide !== null && (n.shadowSide = this.shadowSide), this.sizeAttenuation !== void 0 && (n.sizeAttenuation = this.sizeAttenuation), this.blending !== ka && (n.blending = this.blending), this.side !== M5 && (n.side = this.side), this.vertexColors === true && (n.vertexColors = true), this.opacity < 1 && (n.opacity = this.opacity), this.transparent === true && (n.transparent = true), this.blendSrc !== rN && (n.blendSrc = this.blendSrc), this.blendDst !== zN && (n.blendDst = this.blendDst), this.blendEquation !== xb && (n.blendEquation = this.blendEquation), this.blendSrcAlpha !== null && (n.blendSrcAlpha = this.blendSrcAlpha), this.blendDstAlpha !== null && (n.blendDstAlpha = this.blendDstAlpha), this.blendEquationAlpha !== null && (n.blendEquationAlpha = this.blendEquationAlpha), this.blendColor && this.blendColor.isColor && (n.blendColor = this.blendColor.getHex()), this.blendAlpha !== 0 && (n.blendAlpha = this.blendAlpha), this.depthFunc !== Ja && (n.depthFunc = this.depthFunc), this.depthTest === false && (n.depthTest = this.depthTest), this.depthWrite === false && (n.depthWrite = this.depthWrite), this.colorWrite === false && (n.colorWrite = this.colorWrite), this.stencilWriteMask !== 255 && (n.stencilWriteMask = this.stencilWriteMask), this.stencilFunc !== CI && (n.stencilFunc = this.stencilFunc), this.stencilRef !== 0 && (n.stencilRef = this.stencilRef), this.stencilFuncMask !== 255 && (n.stencilFuncMask = this.stencilFuncMask), this.stencilFail !== Ia && (n.stencilFail = this.stencilFail), this.stencilZFail !== Ia && (n.stencilZFail = this.stencilZFail), this.stencilZPass !== Ia && (n.stencilZPass = this.stencilZPass), this.stencilWrite === true && (n.stencilWrite = this.stencilWrite), this.rotation !== void 0 && this.rotation !== 0 && (n.rotation = this.rotation), this.polygonOffset === true && (n.polygonOffset = true), this.polygonOffsetFactor !== 0 && (n.polygonOffsetFactor = this.polygonOffsetFactor), this.polygonOffsetUnits !== 0 && (n.polygonOffsetUnits = this.polygonOffsetUnits), this.linewidth !== void 0 && this.linewidth !== 1 && (n.linewidth = this.linewidth), this.dashSize !== void 0 && (n.dashSize = this.dashSize), this.gapSize !== void 0 && (n.gapSize = this.gapSize), this.scale !== void 0 && (n.scale = this.scale), this.dithering === true && (n.dithering = true), this.alphaTest > 0 && (n.alphaTest = this.alphaTest), this.alphaHash === true && (n.alphaHash = true), this.alphaToCoverage === true && (n.alphaToCoverage = true), this.premultipliedAlpha === true && (n.premultipliedAlpha = true), this.forceSinglePass === true && (n.forceSinglePass = true), this.wireframe === true && (n.wireframe = true), this.wireframeLinewidth > 1 && (n.wireframeLinewidth = this.wireframeLinewidth), this.wireframeLinecap !== "round" && (n.wireframeLinecap = this.wireframeLinecap), this.wireframeLinejoin !== "round" && (n.wireframeLinejoin = this.wireframeLinejoin), this.flatShading === true && (n.flatShading = true), this.visible === false && (n.visible = false), this.toneMapped === false && (n.toneMapped = false), this.fog === false && (n.fog = false), Object.keys(this.userData).length > 0 && (n.userData = this.userData);
    function v(r) {
      const z = [];
      for (const u in r) {
        const b = r[u];
        delete b.metadata, z.push(b);
      }
      return z;
    }
    if (f) {
      const r = v(e.textures), z = v(e.images);
      r.length > 0 && (n.textures = r), z.length > 0 && (n.images = z);
    }
    return n;
  }
  clone() {
    return new this.constructor().copy(this);
  }
  copy(e) {
    this.name = e.name, this.blending = e.blending, this.side = e.side, this.vertexColors = e.vertexColors, this.opacity = e.opacity, this.transparent = e.transparent, this.blendSrc = e.blendSrc, this.blendDst = e.blendDst, this.blendEquation = e.blendEquation, this.blendSrcAlpha = e.blendSrcAlpha, this.blendDstAlpha = e.blendDstAlpha, this.blendEquationAlpha = e.blendEquationAlpha, this.blendColor.copy(e.blendColor), this.blendAlpha = e.blendAlpha, this.depthFunc = e.depthFunc, this.depthTest = e.depthTest, this.depthWrite = e.depthWrite, this.stencilWriteMask = e.stencilWriteMask, this.stencilFunc = e.stencilFunc, this.stencilRef = e.stencilRef, this.stencilFuncMask = e.stencilFuncMask, this.stencilFail = e.stencilFail, this.stencilZFail = e.stencilZFail, this.stencilZPass = e.stencilZPass, this.stencilWrite = e.stencilWrite;
    const f = e.clippingPlanes;
    let n = null;
    if (f !== null) {
      const v = f.length;
      n = new Array(v);
      for (let r = 0; r !== v; ++r) n[r] = f[r].clone();
    }
    return this.clippingPlanes = n, this.clipIntersection = e.clipIntersection, this.clipShadows = e.clipShadows, this.shadowSide = e.shadowSide, this.colorWrite = e.colorWrite, this.precision = e.precision, this.polygonOffset = e.polygonOffset, this.polygonOffsetFactor = e.polygonOffsetFactor, this.polygonOffsetUnits = e.polygonOffsetUnits, this.dithering = e.dithering, this.alphaTest = e.alphaTest, this.alphaHash = e.alphaHash, this.alphaToCoverage = e.alphaToCoverage, this.premultipliedAlpha = e.premultipliedAlpha, this.forceSinglePass = e.forceSinglePass, this.visible = e.visible, this.toneMapped = e.toneMapped, this.userData = JSON.parse(JSON.stringify(e.userData)), this;
  }
  dispose() {
    this.dispatchEvent({ type: "dispose" });
  }
  set needsUpdate(e) {
    e === true && this.version++;
  }
}
class j6 extends ob {
  constructor(e) {
    super(), this.isMeshBasicMaterial = true, this.type = "MeshBasicMaterial", this.color = new Nf(16777215), this.map = null, this.lightMap = null, this.lightMapIntensity = 1, this.aoMap = null, this.aoMapIntensity = 1, this.specularMap = null, this.alphaMap = null, this.envMap = null, this.envMapRotation = new xz(), this.combine = FI, this.reflectivity = 1, this.refractionRatio = 0.98, this.wireframe = false, this.wireframeLinewidth = 1, this.wireframeLinecap = "round", this.wireframeLinejoin = "round", this.fog = true, this.setValues(e);
  }
  copy(e) {
    return super.copy(e), this.color.copy(e.color), this.map = e.map, this.lightMap = e.lightMap, this.lightMapIntensity = e.lightMapIntensity, this.aoMap = e.aoMap, this.aoMapIntensity = e.aoMapIntensity, this.specularMap = e.specularMap, this.alphaMap = e.alphaMap, this.envMap = e.envMap, this.envMapRotation.copy(e.envMapRotation), this.combine = e.combine, this.reflectivity = e.reflectivity, this.refractionRatio = e.refractionRatio, this.wireframe = e.wireframe, this.wireframeLinewidth = e.wireframeLinewidth, this.wireframeLinecap = e.wireframeLinecap, this.wireframeLinejoin = e.wireframeLinejoin, this.fog = e.fog, this;
  }
}
const uv = new ht();
const x6 = new Me();
let aFt = 0;
class ov {
  constructor(e, f, n = false) {
    if (Array.isArray(e)) throw new TypeError("THREE.BufferAttribute: array should be a Typed Array.");
    this.isBufferAttribute = true, Object.defineProperty(this, "id", { value: aFt++ }), this.name = "", this.array = e, this.itemSize = f, this.count = e !== void 0 ? e.length / f : 0, this.normalized = n, this.usage = _N, this.updateRanges = [], this.gpuType = Zz, this.version = 0;
  }
  onUploadCallback() {
  }
  set needsUpdate(e) {
    e === true && this.version++;
  }
  setUsage(e) {
    return this.usage = e, this;
  }
  addUpdateRange(e, f) {
    this.updateRanges.push({ start: e, count: f });
  }
  clearUpdateRanges() {
    this.updateRanges.length = 0;
  }
  copy(e) {
    return this.name = e.name, this.array = new e.array.constructor(e.array), this.itemSize = e.itemSize, this.count = e.count, this.normalized = e.normalized, this.usage = e.usage, this.gpuType = e.gpuType, this;
  }
  copyAt(e, f, n) {
    e *= this.itemSize, n *= f.itemSize;
    for (let v = 0, r = this.itemSize; v < r; v++) this.array[e + v] = f.array[n + v];
    return this;
  }
  copyArray(e) {
    return this.array.set(e), this;
  }
  applyMatrix3(e) {
    if (this.itemSize === 2) for (let f = 0, n = this.count; f < n; f++) x6.fromBufferAttribute(this, f), x6.applyMatrix3(e), this.setXY(f, x6.x, x6.y);
    else if (this.itemSize === 3) for (let f = 0, n = this.count; f < n; f++) uv.fromBufferAttribute(this, f), uv.applyMatrix3(e), this.setXYZ(f, uv.x, uv.y, uv.z);
    return this;
  }
  applyMatrix4(e) {
    for (let f = 0, n = this.count; f < n; f++) uv.fromBufferAttribute(this, f), uv.applyMatrix4(e), this.setXYZ(f, uv.x, uv.y, uv.z);
    return this;
  }
  applyNormalMatrix(e) {
    for (let f = 0, n = this.count; f < n; f++) uv.fromBufferAttribute(this, f), uv.applyNormalMatrix(e), this.setXYZ(f, uv.x, uv.y, uv.z);
    return this;
  }
  transformDirection(e) {
    for (let f = 0, n = this.count; f < n; f++) uv.fromBufferAttribute(this, f), uv.transformDirection(e), this.setXYZ(f, uv.x, uv.y, uv.z);
    return this;
  }
  set(e, f = 0) {
    return this.array.set(e, f), this;
  }
  getComponent(e, f) {
    let n = this.array[e * this.itemSize + f];
    return this.normalized && (n = y7(n, this.array)), n;
  }
  setComponent(e, f, n) {
    return this.normalized && (n = un(n, this.array)), this.array[e * this.itemSize + f] = n, this;
  }
  getX(e) {
    let f = this.array[e * this.itemSize];
    return this.normalized && (f = y7(f, this.array)), f;
  }
  setX(e, f) {
    return this.normalized && (f = un(f, this.array)), this.array[e * this.itemSize] = f, this;
  }
  getY(e) {
    let f = this.array[e * this.itemSize + 1];
    return this.normalized && (f = y7(f, this.array)), f;
  }
  setY(e, f) {
    return this.normalized && (f = un(f, this.array)), this.array[e * this.itemSize + 1] = f, this;
  }
  getZ(e) {
    let f = this.array[e * this.itemSize + 2];
    return this.normalized && (f = y7(f, this.array)), f;
  }
  setZ(e, f) {
    return this.normalized && (f = un(f, this.array)), this.array[e * this.itemSize + 2] = f, this;
  }
  getW(e) {
    let f = this.array[e * this.itemSize + 3];
    return this.normalized && (f = y7(f, this.array)), f;
  }
  setW(e, f) {
    return this.normalized && (f = un(f, this.array)), this.array[e * this.itemSize + 3] = f, this;
  }
  setXY(e, f, n) {
    return e *= this.itemSize, this.normalized && (f = un(f, this.array), n = un(n, this.array)), this.array[e + 0] = f, this.array[e + 1] = n, this;
  }
  setXYZ(e, f, n, v) {
    return e *= this.itemSize, this.normalized && (f = un(f, this.array), n = un(n, this.array), v = un(v, this.array)), this.array[e + 0] = f, this.array[e + 1] = n, this.array[e + 2] = v, this;
  }
  setXYZW(e, f, n, v, r) {
    return e *= this.itemSize, this.normalized && (f = un(f, this.array), n = un(n, this.array), v = un(v, this.array), r = un(r, this.array)), this.array[e + 0] = f, this.array[e + 1] = n, this.array[e + 2] = v, this.array[e + 3] = r, this;
  }
  onUpload(e) {
    return this.onUploadCallback = e, this;
  }
  clone() {
    return new this.constructor(this.array, this.itemSize).copy(this);
  }
  toJSON() {
    const e = { itemSize: this.itemSize, type: this.array.constructor.name, array: Array.from(this.array), normalized: this.normalized };
    return this.name !== "" && (e.name = this.name), this.usage !== _N && (e.usage = this.usage), e;
  }
}
class lK extends ov {
  constructor(e, f, n) {
    super(new Uint16Array(e), f, n);
  }
}
class XK extends ov {
  constructor(e, f, n) {
    super(new Uint32Array(e), f, n);
  }
}
class ln extends ov {
  constructor(e, f, n) {
    super(new Float32Array(e), f, n);
  }
}
let PFt = 0;
const Dr = new cn();
const jM = new zv();
const r1 = new ht();
const nr = new c9();
const Vs = new c9();
const xv = new ht();
class bv extends Nb {
  constructor() {
    super(), this.isBufferGeometry = true, Object.defineProperty(this, "id", { value: PFt++ }), this.uuid = jz(), this.name = "", this.type = "BufferGeometry", this.index = null, this.indirect = null, this.attributes = {}, this.morphAttributes = {}, this.morphTargetsRelative = false, this.groups = [], this.boundingBox = null, this.boundingSphere = null, this.drawRange = { start: 0, count: 1 / 0 }, this.userData = {};
  }
  getIndex() {
    return this.index;
  }
  setIndex(e) {
    return Array.isArray(e) ? this.index = new (tK(e) ? XK : lK)(e, 1) : this.index = e, this;
  }
  setIndirect(e) {
    return this.indirect = e, this;
  }
  getIndirect() {
    return this.indirect;
  }
  getAttribute(e) {
    return this.attributes[e];
  }
  setAttribute(e, f) {
    return this.attributes[e] = f, this;
  }
  deleteAttribute(e) {
    return delete this.attributes[e], this;
  }
  hasAttribute(e) {
    return this.attributes[e] !== void 0;
  }
  addGroup(e, f, n = 0) {
    this.groups.push({ start: e, count: f, materialIndex: n });
  }
  clearGroups() {
    this.groups = [];
  }
  setDrawRange(e, f) {
    this.drawRange.start = e, this.drawRange.count = f;
  }
  applyMatrix4(e) {
    const f = this.attributes.position;
    f !== void 0 && (f.applyMatrix4(e), f.needsUpdate = true);
    const n = this.attributes.normal;
    if (n !== void 0) {
      const r = new Tf().getNormalMatrix(e);
      n.applyNormalMatrix(r), n.needsUpdate = true;
    }
    const v = this.attributes.tangent;
    return v !== void 0 && (v.transformDirection(e), v.needsUpdate = true), this.boundingBox !== null && this.computeBoundingBox(), this.boundingSphere !== null && this.computeBoundingSphere(), this;
  }
  applyQuaternion(e) {
    return Dr.makeRotationFromQuaternion(e), this.applyMatrix4(Dr), this;
  }
  rotateX(e) {
    return Dr.makeRotationX(e), this.applyMatrix4(Dr), this;
  }
  rotateY(e) {
    return Dr.makeRotationY(e), this.applyMatrix4(Dr), this;
  }
  rotateZ(e) {
    return Dr.makeRotationZ(e), this.applyMatrix4(Dr), this;
  }
  translate(e, f, n) {
    return Dr.makeTranslation(e, f, n), this.applyMatrix4(Dr), this;
  }
  scale(e, f, n) {
    return Dr.makeScale(e, f, n), this.applyMatrix4(Dr), this;
  }
  lookAt(e) {
    return jM.lookAt(e), jM.updateMatrix(), this.applyMatrix4(jM.matrix), this;
  }
  center() {
    return this.computeBoundingBox(), this.boundingBox.getCenter(r1).negate(), this.translate(r1.x, r1.y, r1.z), this;
  }
  setFromPoints(e) {
    const f = this.getAttribute("position");
    if (f === void 0) {
      const n = [];
      for (let v = 0, r = e.length; v < r; v++) {
        const z = e[v];
        n.push(z.x, z.y, z.z || 0);
      }
      this.setAttribute("position", new ln(n, 3));
    } else {
      const n = Math.min(e.length, f.count);
      for (let v = 0; v < n; v++) {
        const r = e[v];
        f.setXYZ(v, r.x, r.y, r.z || 0);
      }
      e.length > f.count && console.warn("THREE.BufferGeometry: Buffer size too small for points data. Use .dispose() and create a new geometry."), f.needsUpdate = true;
    }
    return this;
  }
  computeBoundingBox() {
    this.boundingBox === null && (this.boundingBox = new c9());
    const e = this.attributes.position, f = this.morphAttributes.position;
    if (e && e.isGLBufferAttribute) {
      console.error("THREE.BufferGeometry.computeBoundingBox(): GLBufferAttribute requires a manual bounding box.", this), this.boundingBox.set(new ht(-1 / 0, -1 / 0, -1 / 0), new ht(1 / 0, 1 / 0, 1 / 0));
      return;
    }
    if (e !== void 0) {
      if (this.boundingBox.setFromBufferAttribute(e), f) for (let n = 0, v = f.length; n < v; n++) {
        const r = f[n];
        nr.setFromBufferAttribute(r), this.morphTargetsRelative ? (xv.addVectors(this.boundingBox.min, nr.min), this.boundingBox.expandByPoint(xv), xv.addVectors(this.boundingBox.max, nr.max), this.boundingBox.expandByPoint(xv)) : (this.boundingBox.expandByPoint(nr.min), this.boundingBox.expandByPoint(nr.max));
      }
    } else this.boundingBox.makeEmpty();
    (isNaN(this.boundingBox.min.x) || isNaN(this.boundingBox.min.y) || isNaN(this.boundingBox.min.z)) && console.error('THREE.BufferGeometry.computeBoundingBox(): Computed min/max have NaN values. The "position" attribute is likely to have NaN values.', this);
  }
  computeBoundingSphere() {
    this.boundingSphere === null && (this.boundingSphere = new $a());
    const e = this.attributes.position, f = this.morphAttributes.position;
    if (e && e.isGLBufferAttribute) {
      console.error("THREE.BufferGeometry.computeBoundingSphere(): GLBufferAttribute requires a manual bounding sphere.", this), this.boundingSphere.set(new ht(), 1 / 0);
      return;
    }
    if (e) {
      const n = this.boundingSphere.center;
      if (nr.setFromBufferAttribute(e), f) for (let r = 0, z = f.length; r < z; r++) {
        const u = f[r];
        Vs.setFromBufferAttribute(u), this.morphTargetsRelative ? (xv.addVectors(nr.min, Vs.min), nr.expandByPoint(xv), xv.addVectors(nr.max, Vs.max), nr.expandByPoint(xv)) : (nr.expandByPoint(Vs.min), nr.expandByPoint(Vs.max));
      }
      nr.getCenter(n);
      let v = 0;
      for (let r = 0, z = e.count; r < z; r++) xv.fromBufferAttribute(e, r), v = Math.max(v, n.distanceToSquared(xv));
      if (f) for (let r = 0, z = f.length; r < z; r++) {
        const u = f[r], b = this.morphTargetsRelative;
        for (let a = 0, P = u.count; a < P; a++) xv.fromBufferAttribute(u, a), b && (r1.fromBufferAttribute(e, a), xv.add(r1)), v = Math.max(v, n.distanceToSquared(xv));
      }
      this.boundingSphere.radius = Math.sqrt(v), isNaN(this.boundingSphere.radius) && console.error('THREE.BufferGeometry.computeBoundingSphere(): Computed radius is NaN. The "position" attribute is likely to have NaN values.', this);
    }
  }
  computeTangents() {
    const e = this.index, f = this.attributes;
    if (e === null || f.position === void 0 || f.normal === void 0 || f.uv === void 0) {
      console.error("THREE.BufferGeometry: .computeTangents() failed. Missing required attributes (index, position, normal or uv)");
      return;
    }
    const n = f.position, v = f.normal, r = f.uv;
    this.hasAttribute("tangent") === false && this.setAttribute("tangent", new ov(new Float32Array(4 * n.count), 4));
    const z = this.getAttribute("tangent"), u = [], b = [];
    for (let i = 0; i < n.count; i++) u[i] = new ht(), b[i] = new ht();
    const a = new ht(), P = new ht(), s = new ht(), d = new Me(), c = new Me(), l = new Me(), W = new ht(), X = new ht();
    function y(i, H, M) {
      a.fromBufferAttribute(n, i), P.fromBufferAttribute(n, H), s.fromBufferAttribute(n, M), d.fromBufferAttribute(r, i), c.fromBufferAttribute(r, H), l.fromBufferAttribute(r, M), P.sub(a), s.sub(a), c.sub(d), l.sub(d);
      const p = 1 / (c.x * l.y - l.x * c.y);
      isFinite(p) && (W.copy(P).multiplyScalar(l.y).addScaledVector(s, -c.y).multiplyScalar(p), X.copy(s).multiplyScalar(c.x).addScaledVector(P, -l.x).multiplyScalar(p), u[i].add(W), u[H].add(W), u[M].add(W), b[i].add(X), b[H].add(X), b[M].add(X));
    }
    let m = this.groups;
    m.length === 0 && (m = [{ start: 0, count: e.count }]);
    for (let i = 0, H = m.length; i < H; ++i) {
      const M = m[i], p = M.start, T = M.count;
      for (let w = p, Y = p + T; w < Y; w += 3) y(e.getX(w + 0), e.getX(w + 1), e.getX(w + 2));
    }
    const j = new ht(), x = new ht(), L = new ht(), O = new ht();
    function N(i) {
      L.fromBufferAttribute(v, i), O.copy(L);
      const H = u[i];
      j.copy(H), j.sub(L.multiplyScalar(L.dot(H))).normalize(), x.crossVectors(O, H);
      const p = x.dot(b[i]) < 0 ? -1 : 1;
      z.setXYZW(i, j.x, j.y, j.z, p);
    }
    for (let i = 0, H = m.length; i < H; ++i) {
      const M = m[i], p = M.start, T = M.count;
      for (let w = p, Y = p + T; w < Y; w += 3) N(e.getX(w + 0)), N(e.getX(w + 1)), N(e.getX(w + 2));
    }
  }
  computeVertexNormals() {
    const e = this.index, f = this.getAttribute("position");
    if (f !== void 0) {
      let n = this.getAttribute("normal");
      if (n === void 0) n = new ov(new Float32Array(f.count * 3), 3), this.setAttribute("normal", n);
      else for (let d = 0, c = n.count; d < c; d++) n.setXYZ(d, 0, 0, 0);
      const v = new ht(), r = new ht(), z = new ht(), u = new ht(), b = new ht(), a = new ht(), P = new ht(), s = new ht();
      if (e) for (let d = 0, c = e.count; d < c; d += 3) {
        const l = e.getX(d + 0), W = e.getX(d + 1), X = e.getX(d + 2);
        v.fromBufferAttribute(f, l), r.fromBufferAttribute(f, W), z.fromBufferAttribute(f, X), P.subVectors(z, r), s.subVectors(v, r), P.cross(s), u.fromBufferAttribute(n, l), b.fromBufferAttribute(n, W), a.fromBufferAttribute(n, X), u.add(P), b.add(P), a.add(P), n.setXYZ(l, u.x, u.y, u.z), n.setXYZ(W, b.x, b.y, b.z), n.setXYZ(X, a.x, a.y, a.z);
      }
      else for (let d = 0, c = f.count; d < c; d += 3) v.fromBufferAttribute(f, d + 0), r.fromBufferAttribute(f, d + 1), z.fromBufferAttribute(f, d + 2), P.subVectors(z, r), s.subVectors(v, r), P.cross(s), n.setXYZ(d + 0, P.x, P.y, P.z), n.setXYZ(d + 1, P.x, P.y, P.z), n.setXYZ(d + 2, P.x, P.y, P.z);
      this.normalizeNormals(), n.needsUpdate = true;
    }
  }
  normalizeNormals() {
    const e = this.attributes.normal;
    for (let f = 0, n = e.count; f < n; f++) xv.fromBufferAttribute(e, f), xv.normalize(), e.setXYZ(f, xv.x, xv.y, xv.z);
  }
  toNonIndexed() {
    function e(u, b) {
      const a = u.array, P = u.itemSize, s = u.normalized, d = new a.constructor(b.length * P);
      let c = 0, l = 0;
      for (let W = 0, X = b.length; W < X; W++) {
        u.isInterleavedBufferAttribute ? c = b[W] * u.data.stride + u.offset : c = b[W] * P;
        for (let y = 0; y < P; y++) d[l++] = a[c++];
      }
      return new ov(d, P, s);
    }
    if (this.index === null) return console.warn("THREE.BufferGeometry.toNonIndexed(): BufferGeometry is already non-indexed."), this;
    const f = new bv(), n = this.index.array, v = this.attributes;
    for (const u in v) {
      const b = v[u], a = e(b, n);
      f.setAttribute(u, a);
    }
    const r = this.morphAttributes;
    for (const u in r) {
      const b = [], a = r[u];
      for (let P = 0, s = a.length; P < s; P++) {
        const d = a[P], c = e(d, n);
        b.push(c);
      }
      f.morphAttributes[u] = b;
    }
    f.morphTargetsRelative = this.morphTargetsRelative;
    const z = this.groups;
    for (let u = 0, b = z.length; u < b; u++) {
      const a = z[u];
      f.addGroup(a.start, a.count, a.materialIndex);
    }
    return f;
  }
  toJSON() {
    const e = { metadata: { version: 4.7, type: "BufferGeometry", generator: "BufferGeometry.toJSON" } };
    if (e.uuid = this.uuid, e.type = this.type, this.name !== "" && (e.name = this.name), Object.keys(this.userData).length > 0 && (e.userData = this.userData), this.parameters !== void 0) {
      const b = this.parameters;
      for (const a in b) b[a] !== void 0 && (e[a] = b[a]);
      return e;
    }
    e.data = { attributes: {} };
    const f = this.index;
    f !== null && (e.data.index = { type: f.array.constructor.name, array: Array.prototype.slice.call(f.array) });
    const n = this.attributes;
    for (const b in n) {
      const a = n[b];
      e.data.attributes[b] = a.toJSON(e.data);
    }
    const v = {};
    let r = false;
    for (const b in this.morphAttributes) {
      const a = this.morphAttributes[b], P = [];
      for (let s = 0, d = a.length; s < d; s++) {
        const c = a[s];
        P.push(c.toJSON(e.data));
      }
      P.length > 0 && (v[b] = P, r = true);
    }
    r && (e.data.morphAttributes = v, e.data.morphTargetsRelative = this.morphTargetsRelative);
    const z = this.groups;
    z.length > 0 && (e.data.groups = JSON.parse(JSON.stringify(z)));
    const u = this.boundingSphere;
    return u !== null && (e.data.boundingSphere = u.toJSON()), e;
  }
  clone() {
    return new this.constructor().copy(this);
  }
  copy(e) {
    this.index = null, this.attributes = {}, this.morphAttributes = {}, this.groups = [], this.boundingBox = null, this.boundingSphere = null;
    const f = {};
    this.name = e.name;
    const n = e.index;
    n !== null && this.setIndex(n.clone());
    const v = e.attributes;
    for (const a in v) {
      const P = v[a];
      this.setAttribute(a, P.clone(f));
    }
    const r = e.morphAttributes;
    for (const a in r) {
      const P = [], s = r[a];
      for (let d = 0, c = s.length; d < c; d++) P.push(s[d].clone(f));
      this.morphAttributes[a] = P;
    }
    this.morphTargetsRelative = e.morphTargetsRelative;
    const z = e.groups;
    for (let a = 0, P = z.length; a < P; a++) {
      const s = z[a];
      this.addGroup(s.start, s.count, s.materialIndex);
    }
    const u = e.boundingBox;
    u !== null && (this.boundingBox = u.clone());
    const b = e.boundingSphere;
    return b !== null && (this.boundingSphere = b.clone()), this.drawRange.start = e.drawRange.start, this.drawRange.count = e.drawRange.count, this.userData = e.userData, this;
  }
  dispose() {
    this.dispatchEvent({ type: "dispose" });
  }
}
const WK = new cn();
const wb = new W6();
const L6 = new $a();
const yK = new ht();
const O6 = new ht();
const H6 = new ht();
const N6 = new ht();
const xM = new ht();
const M6 = new ht();
const ZK = new ht();
const i6 = new ht();
class Zn extends zv {
  constructor(e = new bv(), f = new j6()) {
    super(), this.isMesh = true, this.type = "Mesh", this.geometry = e, this.material = f, this.morphTargetDictionary = void 0, this.morphTargetInfluences = void 0, this.count = 1, this.updateMorphTargets();
  }
  copy(e, f) {
    return super.copy(e, f), e.morphTargetInfluences !== void 0 && (this.morphTargetInfluences = e.morphTargetInfluences.slice()), e.morphTargetDictionary !== void 0 && (this.morphTargetDictionary = Object.assign({}, e.morphTargetDictionary)), this.material = Array.isArray(e.material) ? e.material.slice() : e.material, this.geometry = e.geometry, this;
  }
  updateMorphTargets() {
    const f = this.geometry.morphAttributes, n = Object.keys(f);
    if (n.length > 0) {
      const v = f[n[0]];
      if (v !== void 0) {
        this.morphTargetInfluences = [], this.morphTargetDictionary = {};
        for (let r = 0, z = v.length; r < z; r++) {
          const u = v[r].name || String(r);
          this.morphTargetInfluences.push(0), this.morphTargetDictionary[u] = r;
        }
      }
    }
  }
  getVertexPosition(e, f) {
    const n = this.geometry, v = n.attributes.position, r = n.morphAttributes.position, z = n.morphTargetsRelative;
    f.fromBufferAttribute(v, e);
    const u = this.morphTargetInfluences;
    if (r && u) {
      M6.set(0, 0, 0);
      for (let b = 0, a = r.length; b < a; b++) {
        const P = u[b], s = r[b];
        P !== 0 && (xM.fromBufferAttribute(s, e), z ? M6.addScaledVector(xM, P) : M6.addScaledVector(xM.sub(f), P));
      }
      f.add(M6);
    }
    return f;
  }
  raycast(e, f) {
    const n = this.geometry, v = this.material, r = this.matrixWorld;
    v !== void 0 && (n.boundingSphere === null && n.computeBoundingSphere(), L6.copy(n.boundingSphere), L6.applyMatrix4(r), wb.copy(e.ray).recast(e.near), !(L6.containsPoint(wb.origin) === false && (wb.intersectSphere(L6, yK) === null || wb.origin.distanceToSquared(yK) > (e.far - e.near) ** 2)) && (WK.copy(r).invert(), wb.copy(e.ray).applyMatrix4(WK), !(n.boundingBox !== null && wb.intersectsBox(n.boundingBox) === false) && this._computeIntersections(e, f, wb)));
  }
  _computeIntersections(e, f, n) {
    let v;
    const r = this.geometry, z = this.material, u = r.index, b = r.attributes.position, a = r.attributes.uv, P = r.attributes.uv1, s = r.attributes.normal, d = r.groups, c = r.drawRange;
    if (u !== null) if (Array.isArray(z)) for (let l = 0, W = d.length; l < W; l++) {
      const X = d[l], y = z[X.materialIndex], m = Math.max(X.start, c.start), j = Math.min(u.count, Math.min(X.start + X.count, c.start + c.count));
      for (let x = m, L = j; x < L; x += 3) {
        const O = u.getX(x), N = u.getX(x + 1), i = u.getX(x + 2);
        v = p6(this, y, e, n, a, P, s, O, N, i), v && (v.faceIndex = Math.floor(x / 3), v.face.materialIndex = X.materialIndex, f.push(v));
      }
    }
    else {
      const l = Math.max(0, c.start), W = Math.min(u.count, c.start + c.count);
      for (let X = l, y = W; X < y; X += 3) {
        const m = u.getX(X), j = u.getX(X + 1), x = u.getX(X + 2);
        v = p6(this, z, e, n, a, P, s, m, j, x), v && (v.faceIndex = Math.floor(X / 3), f.push(v));
      }
    }
    else if (b !== void 0) if (Array.isArray(z)) for (let l = 0, W = d.length; l < W; l++) {
      const X = d[l], y = z[X.materialIndex], m = Math.max(X.start, c.start), j = Math.min(b.count, Math.min(X.start + X.count, c.start + c.count));
      for (let x = m, L = j; x < L; x += 3) {
        const O = x, N = x + 1, i = x + 2;
        v = p6(this, y, e, n, a, P, s, O, N, i), v && (v.faceIndex = Math.floor(x / 3), v.face.materialIndex = X.materialIndex, f.push(v));
      }
    }
    else {
      const l = Math.max(0, c.start), W = Math.min(b.count, c.start + c.count);
      for (let X = l, y = W; X < y; X += 3) {
        const m = X, j = X + 1, x = X + 2;
        v = p6(this, z, e, n, a, P, s, m, j, x), v && (v.faceIndex = Math.floor(X / 3), f.push(v));
      }
    }
  }
}
function sFt(t, e, f, n, v, r, z, u) {
  let b;
  if (e.side === Y3 ? b = n.intersectTriangle(z, r, v, true, u) : b = n.intersectTriangle(v, r, z, e.side === M5, u), b === null) return null;
  i6.copy(u), i6.applyMatrix4(t.matrixWorld);
  const a = f.ray.origin.distanceTo(i6);
  return a < f.near || a > f.far ? null : { distance: a, point: i6.clone(), object: t };
}
function p6(t, e, f, n, v, r, z, u, b, a) {
  t.getVertexPosition(u, O6), t.getVertexPosition(b, H6), t.getVertexPosition(a, N6);
  const P = sFt(t, e, f, n, O6, H6, N6, ZK);
  if (P) {
    const s = new ht();
    fr.getBarycoord(ZK, O6, H6, N6, s), v && (P.uv = fr.getInterpolatedAttribute(v, u, b, a, s, new Me())), r && (P.uv1 = fr.getInterpolatedAttribute(r, u, b, a, s, new Me())), z && (P.normal = fr.getInterpolatedAttribute(z, u, b, a, s, new ht()), P.normal.dot(n.direction) > 0 && P.normal.multiplyScalar(-1));
    const d = { a: u, b, c: a, normal: new ht(), materialIndex: 0 };
    fr.getNormal(O6, H6, N6, d.normal), P.face = d, P.barycoord = s;
  }
  return P;
}
class Lz extends bv {
  constructor(e = 1, f = 1, n = 1, v = 1, r = 1, z = 1) {
    super(), this.type = "BoxGeometry", this.parameters = { width: e, height: f, depth: n, widthSegments: v, heightSegments: r, depthSegments: z };
    const u = this;
    v = Math.floor(v), r = Math.floor(r), z = Math.floor(z);
    const b = [], a = [], P = [], s = [];
    let d = 0, c = 0;
    l("z", "y", "x", -1, -1, n, f, e, z, r, 0), l("z", "y", "x", 1, -1, n, f, -e, z, r, 1), l("x", "z", "y", 1, 1, e, n, f, v, z, 2), l("x", "z", "y", 1, -1, e, n, -f, v, z, 3), l("x", "y", "z", 1, -1, e, f, n, v, r, 4), l("x", "y", "z", -1, -1, e, f, -n, v, r, 5), this.setIndex(b), this.setAttribute("position", new ln(a, 3)), this.setAttribute("normal", new ln(P, 3)), this.setAttribute("uv", new ln(s, 2));
    function l(W, X, y, m, j, x, L, O, N, i, H) {
      const M = x / N, p = L / i, T = x / 2, w = L / 2, Y = O / 2, q = N + 1, D = i + 1;
      let V = 0, G = 0;
      const R = new ht();
      for (let k = 0; k < D; k++) {
        const K = k * p - w;
        for (let _ = 0; _ < q; _++) {
          const vt = _ * M - T;
          R[W] = vt * m, R[X] = K * j, R[y] = Y, a.push(R.x, R.y, R.z), R[W] = 0, R[X] = 0, R[y] = O > 0 ? 1 : -1, P.push(R.x, R.y, R.z), s.push(_ / N), s.push(1 - k / i), V += 1;
        }
      }
      for (let k = 0; k < i; k++) for (let K = 0; K < N; K++) {
        const _ = d + K + q * k, vt = d + K + q * (k + 1), rt = d + (K + 1) + q * (k + 1), A = d + (K + 1) + q * k;
        b.push(_, vt, A), b.push(vt, rt, A), G += 6;
      }
      u.addGroup(c, G, H), c += G, d += V;
    }
  }
  copy(e) {
    return super.copy(e), this.parameters = Object.assign({}, e.parameters), this;
  }
  static fromJSON(e) {
    return new Lz(e.width, e.height, e.depth, e.widthSegments, e.heightSegments, e.depthSegments);
  }
}
function z1(t) {
  const e = {};
  for (const f in t) {
    e[f] = {};
    for (const n in t[f]) {
      const v = t[f][n];
      v && (v.isColor || v.isMatrix3 || v.isMatrix4 || v.isVector2 || v.isVector3 || v.isVector4 || v.isTexture || v.isQuaternion) ? v.isRenderTargetTexture ? (console.warn("UniformsUtils: Textures of render targets cannot be cloned via cloneUniforms() or mergeUniforms()."), e[f][n] = null) : e[f][n] = v.clone() : Array.isArray(v) ? e[f][n] = v.slice() : e[f][n] = v;
    }
  }
  return e;
}
function c3(t) {
  const e = {};
  for (let f = 0; f < t.length; f++) {
    const n = z1(t[f]);
    for (const v in n) e[v] = n[v];
  }
  return e;
}
function dFt(t) {
  const e = [];
  for (let f = 0; f < t.length; f++) e.push(t[f].clone());
  return e;
}
function mK(t) {
  const e = t.getRenderTarget();
  return e === null ? t.outputColorSpace : e.isXRRenderTarget === true ? e.texture.colorSpace : Uf.workingColorSpace;
}
const cFt = { clone: z1, merge: c3 };
var lFt = `void main() {
	gl_Position = projectionMatrix * modelViewMatrix * vec4( position, 1.0 );
}`;
var XFt = `void main() {
	gl_FragColor = vec4( 1.0, 0.0, 0.0, 1.0 );
}`;
class Y5 extends ob {
  constructor(e) {
    super(), this.isShaderMaterial = true, this.type = "ShaderMaterial", this.defines = {}, this.uniforms = {}, this.uniformsGroups = [], this.vertexShader = lFt, this.fragmentShader = XFt, this.linewidth = 1, this.wireframe = false, this.wireframeLinewidth = 1, this.fog = false, this.lights = false, this.clipping = false, this.forceSinglePass = true, this.extensions = { clipCullDistance: false, multiDraw: false }, this.defaultAttributeValues = { color: [1, 1, 1], uv: [0, 0], uv1: [0, 0] }, this.index0AttributeName = void 0, this.uniformsNeedUpdate = false, this.glslVersion = null, e !== void 0 && this.setValues(e);
  }
  copy(e) {
    return super.copy(e), this.fragmentShader = e.fragmentShader, this.vertexShader = e.vertexShader, this.uniforms = z1(e.uniforms), this.uniformsGroups = dFt(e.uniformsGroups), this.defines = Object.assign({}, e.defines), this.wireframe = e.wireframe, this.wireframeLinewidth = e.wireframeLinewidth, this.fog = e.fog, this.lights = e.lights, this.clipping = e.clipping, this.extensions = Object.assign({}, e.extensions), this.glslVersion = e.glslVersion, this;
  }
  toJSON(e) {
    const f = super.toJSON(e);
    f.glslVersion = this.glslVersion, f.uniforms = {};
    for (const v in this.uniforms) {
      const z = this.uniforms[v].value;
      z && z.isTexture ? f.uniforms[v] = { type: "t", value: z.toJSON(e).uuid } : z && z.isColor ? f.uniforms[v] = { type: "c", value: z.getHex() } : z && z.isVector2 ? f.uniforms[v] = { type: "v2", value: z.toArray() } : z && z.isVector3 ? f.uniforms[v] = { type: "v3", value: z.toArray() } : z && z.isVector4 ? f.uniforms[v] = { type: "v4", value: z.toArray() } : z && z.isMatrix3 ? f.uniforms[v] = { type: "m3", value: z.toArray() } : z && z.isMatrix4 ? f.uniforms[v] = { type: "m4", value: z.toArray() } : f.uniforms[v] = { value: z };
    }
    Object.keys(this.defines).length > 0 && (f.defines = this.defines), f.vertexShader = this.vertexShader, f.fragmentShader = this.fragmentShader, f.lights = this.lights, f.clipping = this.clipping;
    const n = {};
    for (const v in this.extensions) this.extensions[v] === true && (n[v] = true);
    return Object.keys(n).length > 0 && (f.extensions = n), f;
  }
}
class jK extends zv {
  constructor() {
    super(), this.isCamera = true, this.type = "Camera", this.matrixWorldInverse = new cn(), this.projectionMatrix = new cn(), this.projectionMatrixInverse = new cn(), this.coordinateSystem = mz, this._reversedDepth = false;
  }
  get reversedDepth() {
    return this._reversedDepth;
  }
  copy(e, f) {
    return super.copy(e, f), this.matrixWorldInverse.copy(e.matrixWorldInverse), this.projectionMatrix.copy(e.projectionMatrix), this.projectionMatrixInverse.copy(e.projectionMatrixInverse), this.coordinateSystem = e.coordinateSystem, this;
  }
  getWorldDirection(e) {
    return super.getWorldDirection(e).negate();
  }
  updateMatrixWorld(e) {
    super.updateMatrixWorld(e), this.matrixWorldInverse.copy(this.matrixWorld).invert();
  }
  updateWorldMatrix(e, f) {
    super.updateWorldMatrix(e, f), this.matrixWorldInverse.copy(this.matrixWorld).invert();
  }
  clone() {
    return new this.constructor().copy(this);
  }
}
const D5 = new ht();
const xK = new Me();
const LK = new Me();
class qr extends jK {
  constructor(e = 50, f = 1, n = 0.1, v = 2e3) {
    super(), this.isPerspectiveCamera = true, this.type = "PerspectiveCamera", this.fov = e, this.zoom = 1, this.near = n, this.far = v, this.focus = 10, this.aspect = f, this.view = null, this.filmGauge = 35, this.filmOffset = 0, this.updateProjectionMatrix();
  }
  copy(e, f) {
    return super.copy(e, f), this.fov = e.fov, this.zoom = e.zoom, this.near = e.near, this.far = e.far, this.focus = e.focus, this.aspect = e.aspect, this.view = e.view === null ? null : Object.assign({}, e.view), this.filmGauge = e.filmGauge, this.filmOffset = e.filmOffset, this;
  }
  setFocalLength(e) {
    const f = 0.5 * this.getFilmHeight() / e;
    this.fov = ps * 2 * Math.atan(f), this.updateProjectionMatrix();
  }
  getFocalLength() {
    const e = Math.tan(Ka * 0.5 * this.fov);
    return 0.5 * this.getFilmHeight() / e;
  }
  getEffectiveFOV() {
    return ps * 2 * Math.atan(Math.tan(Ka * 0.5 * this.fov) / this.zoom);
  }
  getFilmWidth() {
    return this.filmGauge * Math.min(this.aspect, 1);
  }
  getFilmHeight() {
    return this.filmGauge / Math.max(this.aspect, 1);
  }
  getViewBounds(e, f, n) {
    D5.set(-1, -1, 0.5).applyMatrix4(this.projectionMatrixInverse), f.set(D5.x, D5.y).multiplyScalar(-e / D5.z), D5.set(1, 1, 0.5).applyMatrix4(this.projectionMatrixInverse), n.set(D5.x, D5.y).multiplyScalar(-e / D5.z);
  }
  getViewSize(e, f) {
    return this.getViewBounds(e, xK, LK), f.subVectors(LK, xK);
  }
  setViewOffset(e, f, n, v, r, z) {
    this.aspect = e / f, this.view === null && (this.view = { enabled: true, fullWidth: 1, fullHeight: 1, offsetX: 0, offsetY: 0, width: 1, height: 1 }), this.view.enabled = true, this.view.fullWidth = e, this.view.fullHeight = f, this.view.offsetX = n, this.view.offsetY = v, this.view.width = r, this.view.height = z, this.updateProjectionMatrix();
  }
  clearViewOffset() {
    this.view !== null && (this.view.enabled = false), this.updateProjectionMatrix();
  }
  updateProjectionMatrix() {
    const e = this.near;
    let f = e * Math.tan(Ka * 0.5 * this.fov) / this.zoom, n = 2 * f, v = this.aspect * n, r = -0.5 * v;
    const z = this.view;
    if (this.view !== null && this.view.enabled) {
      const b = z.fullWidth, a = z.fullHeight;
      r += z.offsetX * v / b, f -= z.offsetY * n / a, v *= z.width / b, n *= z.height / a;
    }
    const u = this.filmOffset;
    u !== 0 && (r += e * u / this.getFilmWidth()), this.projectionMatrix.makePerspective(r, r + v, f, f - n, e, this.far, this.coordinateSystem, this.reversedDepth), this.projectionMatrixInverse.copy(this.projectionMatrix).invert();
  }
  toJSON(e) {
    const f = super.toJSON(e);
    return f.object.fov = this.fov, f.object.zoom = this.zoom, f.object.near = this.near, f.object.far = this.far, f.object.focus = this.focus, f.object.aspect = this.aspect, this.view !== null && (f.object.view = Object.assign({}, this.view)), f.object.filmGauge = this.filmGauge, f.object.filmOffset = this.filmOffset, f;
  }
}
const u1 = -90;
const b1 = 1;
class OK extends zv {
  constructor(e, f, n) {
    super(), this.type = "CubeCamera", this.renderTarget = n, this.coordinateSystem = null, this.activeMipmapLevel = 0;
    const v = new qr(u1, b1, e, f);
    v.layers = this.layers, this.add(v);
    const r = new qr(u1, b1, e, f);
    r.layers = this.layers, this.add(r);
    const z = new qr(u1, b1, e, f);
    z.layers = this.layers, this.add(z);
    const u = new qr(u1, b1, e, f);
    u.layers = this.layers, this.add(u);
    const b = new qr(u1, b1, e, f);
    b.layers = this.layers, this.add(b);
    const a = new qr(u1, b1, e, f);
    a.layers = this.layers, this.add(a);
  }
  updateCoordinateSystem() {
    const e = this.coordinateSystem, f = this.children.concat(), [n, v, r, z, u, b] = f;
    for (const a of f) this.remove(a);
    if (e === mz) n.up.set(0, 1, 0), n.lookAt(1, 0, 0), v.up.set(0, 1, 0), v.lookAt(-1, 0, 0), r.up.set(0, 0, -1), r.lookAt(0, 1, 0), z.up.set(0, 0, 1), z.lookAt(0, -1, 0), u.up.set(0, 1, 0), u.lookAt(0, 0, 1), b.up.set(0, 1, 0), b.lookAt(0, 0, -1);
    else if (e === b6) n.up.set(0, -1, 0), n.lookAt(-1, 0, 0), v.up.set(0, -1, 0), v.lookAt(1, 0, 0), r.up.set(0, 0, 1), r.lookAt(0, 1, 0), z.up.set(0, 0, -1), z.lookAt(0, -1, 0), u.up.set(0, -1, 0), u.lookAt(0, 0, 1), b.up.set(0, -1, 0), b.lookAt(0, 0, -1);
    else throw new Error("THREE.CubeCamera.updateCoordinateSystem(): Invalid coordinate system: " + e);
    for (const a of f) this.add(a), a.updateMatrixWorld();
  }
  update(e, f) {
    this.parent === null && this.updateMatrixWorld();
    const { renderTarget: n, activeMipmapLevel: v } = this;
    this.coordinateSystem !== e.coordinateSystem && (this.coordinateSystem = e.coordinateSystem, this.updateCoordinateSystem());
    const [r, z, u, b, a, P] = this.children, s = e.getRenderTarget(), d = e.getActiveCubeFace(), c = e.getActiveMipmapLevel(), l = e.xr.enabled;
    e.xr.enabled = false;
    const W = n.texture.generateMipmaps;
    n.texture.generateMipmaps = false, e.setRenderTarget(n, 0, v), e.render(f, r), e.setRenderTarget(n, 1, v), e.render(f, z), e.setRenderTarget(n, 2, v), e.render(f, u), e.setRenderTarget(n, 3, v), e.render(f, b), e.setRenderTarget(n, 4, v), e.render(f, a), n.texture.generateMipmaps = W, e.setRenderTarget(n, 5, v), e.render(f, P), e.setRenderTarget(s, d, c), e.xr.enabled = l, n.texture.needsPMREMUpdate = true;
  }
}
class HK extends Tv {
  constructor(e = [], f = ga, n, v, r, z, u, b, a, P) {
    super(e, f, n, v, r, z, u, b, a, P), this.isCubeTexture = true, this.flipY = false;
  }
  get images() {
    return this.image;
  }
  set images(e) {
    this.image = e;
  }
}
class NK extends ib {
  constructor(e = 1, f = {}) {
    super(e, e, f), this.isWebGLCubeRenderTarget = true;
    const n = { width: e, height: e, depth: 1 }, v = [n, n, n, n, n, n];
    this.texture = new HK(v), this._setTextureOptions(f), this.texture.isRenderTargetTexture = true;
  }
  fromEquirectangularTexture(e, f) {
    this.texture.type = f.type, this.texture.colorSpace = f.colorSpace, this.texture.generateMipmaps = f.generateMipmaps, this.texture.minFilter = f.minFilter, this.texture.magFilter = f.magFilter;
    const n = { uniforms: { tEquirect: { value: null } }, vertexShader: `

				varying vec3 vWorldDirection;

				vec3 transformDirection( in vec3 dir, in mat4 matrix ) {

					return normalize( ( matrix * vec4( dir, 0.0 ) ).xyz );

				}

				void main() {

					vWorldDirection = transformDirection( position, modelMatrix );

					#include <begin_vertex>
					#include <project_vertex>

				}
			`, fragmentShader: `

				uniform sampler2D tEquirect;

				varying vec3 vWorldDirection;

				#include <common>

				void main() {

					vec3 direction = normalize( vWorldDirection );

					vec2 sampleUV = equirectUv( direction );

					gl_FragColor = texture2D( tEquirect, sampleUV );

				}
			` }, v = new Lz(5, 5, 5), r = new Y5({ name: "CubemapFromEquirect", uniforms: z1(n.uniforms), vertexShader: n.vertexShader, fragmentShader: n.fragmentShader, side: Y3, blending: i5 });
    r.uniforms.tEquirect.value = f;
    const z = new Zn(v, r), u = f.minFilter;
    return f.minFilter === Ob && (f.minFilter = Wz), new OK(1, 10, this).update(e, z), f.minFilter = u, z.geometry.dispose(), z.material.dispose(), this;
  }
  clear(e, f = true, n = true, v = true) {
    const r = e.getRenderTarget();
    for (let z = 0; z < 6; z++) e.setRenderTarget(this, z), e.clear(f, n, v);
    e.setRenderTarget(r);
  }
}
class D3 extends zv {
  constructor() {
    super(), this.isGroup = true, this.type = "Group";
  }
}
const WFt = { type: "move" };
class LM {
  constructor() {
    this._targetRay = null, this._grip = null, this._hand = null;
  }
  getHandSpace() {
    return this._hand === null && (this._hand = new D3(), this._hand.matrixAutoUpdate = false, this._hand.visible = false, this._hand.joints = {}, this._hand.inputState = { pinching: false }), this._hand;
  }
  getTargetRaySpace() {
    return this._targetRay === null && (this._targetRay = new D3(), this._targetRay.matrixAutoUpdate = false, this._targetRay.visible = false, this._targetRay.hasLinearVelocity = false, this._targetRay.linearVelocity = new ht(), this._targetRay.hasAngularVelocity = false, this._targetRay.angularVelocity = new ht()), this._targetRay;
  }
  getGripSpace() {
    return this._grip === null && (this._grip = new D3(), this._grip.matrixAutoUpdate = false, this._grip.visible = false, this._grip.hasLinearVelocity = false, this._grip.linearVelocity = new ht(), this._grip.hasAngularVelocity = false, this._grip.angularVelocity = new ht()), this._grip;
  }
  dispatchEvent(e) {
    return this._targetRay !== null && this._targetRay.dispatchEvent(e), this._grip !== null && this._grip.dispatchEvent(e), this._hand !== null && this._hand.dispatchEvent(e), this;
  }
  connect(e) {
    if (e && e.hand) {
      const f = this._hand;
      if (f) for (const n of e.hand.values()) this._getHandJoint(f, n);
    }
    return this.dispatchEvent({ type: "connected", data: e }), this;
  }
  disconnect(e) {
    return this.dispatchEvent({ type: "disconnected", data: e }), this._targetRay !== null && (this._targetRay.visible = false), this._grip !== null && (this._grip.visible = false), this._hand !== null && (this._hand.visible = false), this;
  }
  update(e, f, n) {
    let v = null, r = null, z = null;
    const u = this._targetRay, b = this._grip, a = this._hand;
    if (e && f.session.visibilityState !== "visible-blurred") {
      if (a && e.hand) {
        z = true;
        for (const W of e.hand.values()) {
          const X = f.getJointPose(W, n), y = this._getHandJoint(a, W);
          X !== null && (y.matrix.fromArray(X.transform.matrix), y.matrix.decompose(y.position, y.rotation, y.scale), y.matrixWorldNeedsUpdate = true, y.jointRadius = X.radius), y.visible = X !== null;
        }
        const P = a.joints["index-finger-tip"], s = a.joints["thumb-tip"], d = P.position.distanceTo(s.position), c = 0.02, l = 5e-3;
        a.inputState.pinching && d > c + l ? (a.inputState.pinching = false, this.dispatchEvent({ type: "pinchend", handedness: e.handedness, target: this })) : !a.inputState.pinching && d <= c - l && (a.inputState.pinching = true, this.dispatchEvent({ type: "pinchstart", handedness: e.handedness, target: this }));
      } else b !== null && e.gripSpace && (r = f.getPose(e.gripSpace, n), r !== null && (b.matrix.fromArray(r.transform.matrix), b.matrix.decompose(b.position, b.rotation, b.scale), b.matrixWorldNeedsUpdate = true, r.linearVelocity ? (b.hasLinearVelocity = true, b.linearVelocity.copy(r.linearVelocity)) : b.hasLinearVelocity = false, r.angularVelocity ? (b.hasAngularVelocity = true, b.angularVelocity.copy(r.angularVelocity)) : b.hasAngularVelocity = false));
      u !== null && (v = f.getPose(e.targetRaySpace, n), v === null && r !== null && (v = r), v !== null && (u.matrix.fromArray(v.transform.matrix), u.matrix.decompose(u.position, u.rotation, u.scale), u.matrixWorldNeedsUpdate = true, v.linearVelocity ? (u.hasLinearVelocity = true, u.linearVelocity.copy(v.linearVelocity)) : u.hasLinearVelocity = false, v.angularVelocity ? (u.hasAngularVelocity = true, u.angularVelocity.copy(v.angularVelocity)) : u.hasAngularVelocity = false, this.dispatchEvent(WFt)));
    }
    return u !== null && (u.visible = v !== null), b !== null && (b.visible = r !== null), a !== null && (a.visible = z !== null), this;
  }
  _getHandJoint(e, f) {
    if (e.joints[f.jointName] === void 0) {
      const n = new D3();
      n.matrixAutoUpdate = false, n.visible = false, e.joints[f.jointName] = n, e.add(n);
    }
    return e.joints[f.jointName];
  }
}
class OM {
  constructor(e, f = 1, n = 1e3) {
    this.isFog = true, this.name = "", this.color = new Nf(e), this.near = f, this.far = n;
  }
  clone() {
    return new OM(this.color, this.near, this.far);
  }
  toJSON() {
    return { type: "Fog", name: this.name, color: this.color.getHex(), near: this.near, far: this.far };
  }
}
class yFt extends zv {
  constructor() {
    super(), this.isScene = true, this.type = "Scene", this.background = null, this.environment = null, this.fog = null, this.backgroundBlurriness = 0, this.backgroundIntensity = 1, this.backgroundRotation = new xz(), this.environmentIntensity = 1, this.environmentRotation = new xz(), this.overrideMaterial = null, typeof __THREE_DEVTOOLS__ < "u" && __THREE_DEVTOOLS__.dispatchEvent(new CustomEvent("observe", { detail: this }));
  }
  copy(e, f) {
    return super.copy(e, f), e.background !== null && (this.background = e.background.clone()), e.environment !== null && (this.environment = e.environment.clone()), e.fog !== null && (this.fog = e.fog.clone()), this.backgroundBlurriness = e.backgroundBlurriness, this.backgroundIntensity = e.backgroundIntensity, this.backgroundRotation.copy(e.backgroundRotation), this.environmentIntensity = e.environmentIntensity, this.environmentRotation.copy(e.environmentRotation), e.overrideMaterial !== null && (this.overrideMaterial = e.overrideMaterial.clone()), this.matrixAutoUpdate = e.matrixAutoUpdate, this;
  }
  toJSON(e) {
    const f = super.toJSON(e);
    return this.fog !== null && (f.object.fog = this.fog.toJSON()), this.backgroundBlurriness > 0 && (f.object.backgroundBlurriness = this.backgroundBlurriness), this.backgroundIntensity !== 1 && (f.object.backgroundIntensity = this.backgroundIntensity), f.object.backgroundRotation = this.backgroundRotation.toArray(), this.environmentIntensity !== 1 && (f.object.environmentIntensity = this.environmentIntensity), f.object.environmentRotation = this.environmentRotation.toArray(), f;
  }
}
class ZFt {
  constructor(e, f) {
    this.isInterleavedBuffer = true, this.array = e, this.stride = f, this.count = e !== void 0 ? e.length / f : 0, this.usage = _N, this.updateRanges = [], this.version = 0, this.uuid = jz();
  }
  onUploadCallback() {
  }
  set needsUpdate(e) {
    e === true && this.version++;
  }
  setUsage(e) {
    return this.usage = e, this;
  }
  addUpdateRange(e, f) {
    this.updateRanges.push({ start: e, count: f });
  }
  clearUpdateRanges() {
    this.updateRanges.length = 0;
  }
  copy(e) {
    return this.array = new e.array.constructor(e.array), this.count = e.count, this.stride = e.stride, this.usage = e.usage, this;
  }
  copyAt(e, f, n) {
    e *= this.stride, n *= f.stride;
    for (let v = 0, r = this.stride; v < r; v++) this.array[e + v] = f.array[n + v];
    return this;
  }
  set(e, f = 0) {
    return this.array.set(e, f), this;
  }
  clone(e) {
    e.arrayBuffers === void 0 && (e.arrayBuffers = {}), this.array.buffer._uuid === void 0 && (this.array.buffer._uuid = jz()), e.arrayBuffers[this.array.buffer._uuid] === void 0 && (e.arrayBuffers[this.array.buffer._uuid] = this.array.slice(0).buffer);
    const f = new this.array.constructor(e.arrayBuffers[this.array.buffer._uuid]), n = new this.constructor(f, this.stride);
    return n.setUsage(this.usage), n;
  }
  onUpload(e) {
    return this.onUploadCallback = e, this;
  }
  toJSON(e) {
    return e.arrayBuffers === void 0 && (e.arrayBuffers = {}), this.array.buffer._uuid === void 0 && (this.array.buffer._uuid = jz()), e.arrayBuffers[this.array.buffer._uuid] === void 0 && (e.arrayBuffers[this.array.buffer._uuid] = Array.from(new Uint32Array(this.array.buffer))), { uuid: this.uuid, buffer: this.array.buffer._uuid, type: this.array.constructor.name, stride: this.stride };
  }
}
const l3 = new ht();
class T6 {
  constructor(e, f, n, v = false) {
    this.isInterleavedBufferAttribute = true, this.name = "", this.data = e, this.itemSize = f, this.offset = n, this.normalized = v;
  }
  get count() {
    return this.data.count;
  }
  get array() {
    return this.data.array;
  }
  set needsUpdate(e) {
    this.data.needsUpdate = e;
  }
  applyMatrix4(e) {
    for (let f = 0, n = this.data.count; f < n; f++) l3.fromBufferAttribute(this, f), l3.applyMatrix4(e), this.setXYZ(f, l3.x, l3.y, l3.z);
    return this;
  }
  applyNormalMatrix(e) {
    for (let f = 0, n = this.count; f < n; f++) l3.fromBufferAttribute(this, f), l3.applyNormalMatrix(e), this.setXYZ(f, l3.x, l3.y, l3.z);
    return this;
  }
  transformDirection(e) {
    for (let f = 0, n = this.count; f < n; f++) l3.fromBufferAttribute(this, f), l3.transformDirection(e), this.setXYZ(f, l3.x, l3.y, l3.z);
    return this;
  }
  getComponent(e, f) {
    let n = this.array[e * this.data.stride + this.offset + f];
    return this.normalized && (n = y7(n, this.array)), n;
  }
  setComponent(e, f, n) {
    return this.normalized && (n = un(n, this.array)), this.data.array[e * this.data.stride + this.offset + f] = n, this;
  }
  setX(e, f) {
    return this.normalized && (f = un(f, this.array)), this.data.array[e * this.data.stride + this.offset] = f, this;
  }
  setY(e, f) {
    return this.normalized && (f = un(f, this.array)), this.data.array[e * this.data.stride + this.offset + 1] = f, this;
  }
  setZ(e, f) {
    return this.normalized && (f = un(f, this.array)), this.data.array[e * this.data.stride + this.offset + 2] = f, this;
  }
  setW(e, f) {
    return this.normalized && (f = un(f, this.array)), this.data.array[e * this.data.stride + this.offset + 3] = f, this;
  }
  getX(e) {
    let f = this.data.array[e * this.data.stride + this.offset];
    return this.normalized && (f = y7(f, this.array)), f;
  }
  getY(e) {
    let f = this.data.array[e * this.data.stride + this.offset + 1];
    return this.normalized && (f = y7(f, this.array)), f;
  }
  getZ(e) {
    let f = this.data.array[e * this.data.stride + this.offset + 2];
    return this.normalized && (f = y7(f, this.array)), f;
  }
  getW(e) {
    let f = this.data.array[e * this.data.stride + this.offset + 3];
    return this.normalized && (f = y7(f, this.array)), f;
  }
  setXY(e, f, n) {
    return e = e * this.data.stride + this.offset, this.normalized && (f = un(f, this.array), n = un(n, this.array)), this.data.array[e + 0] = f, this.data.array[e + 1] = n, this;
  }
  setXYZ(e, f, n, v) {
    return e = e * this.data.stride + this.offset, this.normalized && (f = un(f, this.array), n = un(n, this.array), v = un(v, this.array)), this.data.array[e + 0] = f, this.data.array[e + 1] = n, this.data.array[e + 2] = v, this;
  }
  setXYZW(e, f, n, v, r) {
    return e = e * this.data.stride + this.offset, this.normalized && (f = un(f, this.array), n = un(n, this.array), v = un(v, this.array), r = un(r, this.array)), this.data.array[e + 0] = f, this.data.array[e + 1] = n, this.data.array[e + 2] = v, this.data.array[e + 3] = r, this;
  }
  clone(e) {
    if (e === void 0) {
      console.log("THREE.InterleavedBufferAttribute.clone(): Cloning an interleaved buffer attribute will de-interleave buffer data.");
      const f = [];
      for (let n = 0; n < this.count; n++) {
        const v = n * this.data.stride + this.offset;
        for (let r = 0; r < this.itemSize; r++) f.push(this.data.array[v + r]);
      }
      return new ov(new this.array.constructor(f), this.itemSize, this.normalized);
    } else return e.interleavedBuffers === void 0 && (e.interleavedBuffers = {}), e.interleavedBuffers[this.data.uuid] === void 0 && (e.interleavedBuffers[this.data.uuid] = this.data.clone(e)), new T6(e.interleavedBuffers[this.data.uuid], this.itemSize, this.offset, this.normalized);
  }
  toJSON(e) {
    if (e === void 0) {
      console.log("THREE.InterleavedBufferAttribute.toJSON(): Serializing an interleaved buffer attribute will de-interleave buffer data.");
      const f = [];
      for (let n = 0; n < this.count; n++) {
        const v = n * this.data.stride + this.offset;
        for (let r = 0; r < this.itemSize; r++) f.push(this.data.array[v + r]);
      }
      return { itemSize: this.itemSize, type: this.array.constructor.name, array: f, normalized: this.normalized };
    } else return e.interleavedBuffers === void 0 && (e.interleavedBuffers = {}), e.interleavedBuffers[this.data.uuid] === void 0 && (e.interleavedBuffers[this.data.uuid] = this.data.toJSON(e)), { isInterleavedBufferAttribute: true, itemSize: this.itemSize, data: this.data.uuid, offset: this.offset, normalized: this.normalized };
  }
}
class MK extends ob {
  constructor(e) {
    super(), this.isSpriteMaterial = true, this.type = "SpriteMaterial", this.color = new Nf(16777215), this.map = null, this.alphaMap = null, this.rotation = 0, this.sizeAttenuation = true, this.transparent = true, this.fog = true, this.setValues(e);
  }
  copy(e) {
    return super.copy(e), this.color.copy(e.color), this.map = e.map, this.alphaMap = e.alphaMap, this.rotation = e.rotation, this.sizeAttenuation = e.sizeAttenuation, this.fog = e.fog, this;
  }
}
let a1;
const Ys = new ht();
const P1 = new ht();
const s1 = new ht();
const d1 = new Me();
const Ds = new Me();
const iK = new cn();
const o6 = new ht();
const qs = new ht();
const w6 = new ht();
const pK = new Me();
const HM = new Me();
const TK = new Me();
class mFt extends zv {
  constructor(e = new MK()) {
    if (super(), this.isSprite = true, this.type = "Sprite", a1 === void 0) {
      a1 = new bv();
      const f = new Float32Array([-0.5, -0.5, 0, 0, 0, 0.5, -0.5, 0, 1, 0, 0.5, 0.5, 0, 1, 1, -0.5, 0.5, 0, 0, 1]), n = new ZFt(f, 5);
      a1.setIndex([0, 1, 2, 0, 2, 3]), a1.setAttribute("position", new T6(n, 3, 0, false)), a1.setAttribute("uv", new T6(n, 2, 3, false));
    }
    this.geometry = a1, this.material = e, this.center = new Me(0.5, 0.5), this.count = 1;
  }
  raycast(e, f) {
    e.camera === null && console.error('THREE.Sprite: "Raycaster.camera" needs to be set in order to raycast against sprites.'), P1.setFromMatrixScale(this.matrixWorld), iK.copy(e.camera.matrixWorld), this.modelViewMatrix.multiplyMatrices(e.camera.matrixWorldInverse, this.matrixWorld), s1.setFromMatrixPosition(this.modelViewMatrix), e.camera.isPerspectiveCamera && this.material.sizeAttenuation === false && P1.multiplyScalar(-s1.z);
    const n = this.material.rotation;
    let v, r;
    n !== 0 && (r = Math.cos(n), v = Math.sin(n));
    const z = this.center;
    h6(o6.set(-0.5, -0.5, 0), s1, z, P1, v, r), h6(qs.set(0.5, -0.5, 0), s1, z, P1, v, r), h6(w6.set(0.5, 0.5, 0), s1, z, P1, v, r), pK.set(0, 0), HM.set(1, 0), TK.set(1, 1);
    let u = e.ray.intersectTriangle(o6, qs, w6, false, Ys);
    if (u === null && (h6(qs.set(-0.5, 0.5, 0), s1, z, P1, v, r), HM.set(0, 1), u = e.ray.intersectTriangle(o6, w6, qs, false, Ys), u === null)) return;
    const b = e.ray.origin.distanceTo(Ys);
    b < e.near || b > e.far || f.push({ distance: b, point: Ys.clone(), uv: fr.getInterpolation(Ys, o6, qs, w6, pK, HM, TK, new Me()), face: null, object: this });
  }
  copy(e, f) {
    return super.copy(e, f), e.center !== void 0 && this.center.copy(e.center), this.material = e.material, this;
  }
}
function h6(t, e, f, n, v, r) {
  d1.subVectors(t, f).addScalar(0.5).multiply(n), v !== void 0 ? (Ds.x = r * d1.x - v * d1.y, Ds.y = v * d1.x + r * d1.y) : Ds.copy(d1), t.copy(e), t.x += Ds.x, t.y += Ds.y, t.applyMatrix4(iK);
}
class jFt extends Tv {
  constructor(e = null, f = 1, n = 1, v, r, z, u, b, a = tr, P = tr, s, d) {
    super(null, z, u, b, a, P, v, r, s, d), this.isDataTexture = true, this.image = { data: e, width: f, height: n }, this.generateMipmaps = false, this.flipY = false, this.unpackAlignment = 1;
  }
}
class oK extends ov {
  constructor(e, f, n, v = 1) {
    super(e, f, n), this.isInstancedBufferAttribute = true, this.meshPerAttribute = v;
  }
  copy(e) {
    return super.copy(e), this.meshPerAttribute = e.meshPerAttribute, this;
  }
  toJSON() {
    const e = super.toJSON();
    return e.meshPerAttribute = this.meshPerAttribute, e.isInstancedBufferAttribute = true, e;
  }
}
const c1 = new cn();
const wK = new cn();
const G6 = [];
const hK = new c9();
const xFt = new cn();
const Fs = new Zn();
const Rs = new $a();
class ks extends Zn {
  constructor(e, f, n) {
    super(e, f), this.isInstancedMesh = true, this.instanceMatrix = new oK(new Float32Array(n * 16), 16), this.instanceColor = null, this.morphTexture = null, this.count = n, this.boundingBox = null, this.boundingSphere = null;
    for (let v = 0; v < n; v++) this.setMatrixAt(v, xFt);
  }
  computeBoundingBox() {
    const e = this.geometry, f = this.count;
    this.boundingBox === null && (this.boundingBox = new c9()), e.boundingBox === null && e.computeBoundingBox(), this.boundingBox.makeEmpty();
    for (let n = 0; n < f; n++) this.getMatrixAt(n, c1), hK.copy(e.boundingBox).applyMatrix4(c1), this.boundingBox.union(hK);
  }
  computeBoundingSphere() {
    const e = this.geometry, f = this.count;
    this.boundingSphere === null && (this.boundingSphere = new $a()), e.boundingSphere === null && e.computeBoundingSphere(), this.boundingSphere.makeEmpty();
    for (let n = 0; n < f; n++) this.getMatrixAt(n, c1), Rs.copy(e.boundingSphere).applyMatrix4(c1), this.boundingSphere.union(Rs);
  }
  copy(e, f) {
    return super.copy(e, f), this.instanceMatrix.copy(e.instanceMatrix), e.morphTexture !== null && (this.morphTexture = e.morphTexture.clone()), e.instanceColor !== null && (this.instanceColor = e.instanceColor.clone()), this.count = e.count, e.boundingBox !== null && (this.boundingBox = e.boundingBox.clone()), e.boundingSphere !== null && (this.boundingSphere = e.boundingSphere.clone()), this;
  }
  getColorAt(e, f) {
    f.fromArray(this.instanceColor.array, e * 3);
  }
  getMatrixAt(e, f) {
    f.fromArray(this.instanceMatrix.array, e * 16);
  }
  getMorphAt(e, f) {
    const n = f.morphTargetInfluences, v = this.morphTexture.source.data.data, r = n.length + 1, z = e * r + 1;
    for (let u = 0; u < n.length; u++) n[u] = v[z + u];
  }
  raycast(e, f) {
    const n = this.matrixWorld, v = this.count;
    if (Fs.geometry = this.geometry, Fs.material = this.material, Fs.material !== void 0 && (this.boundingSphere === null && this.computeBoundingSphere(), Rs.copy(this.boundingSphere), Rs.applyMatrix4(n), e.ray.intersectsSphere(Rs) !== false)) for (let r = 0; r < v; r++) {
      this.getMatrixAt(r, c1), wK.multiplyMatrices(n, c1), Fs.matrixWorld = wK, Fs.raycast(e, G6);
      for (let z = 0, u = G6.length; z < u; z++) {
        const b = G6[z];
        b.instanceId = r, b.object = this, f.push(b);
      }
      G6.length = 0;
    }
  }
  setColorAt(e, f) {
    this.instanceColor === null && (this.instanceColor = new oK(new Float32Array(this.instanceMatrix.count * 3).fill(1), 3)), f.toArray(this.instanceColor.array, e * 3);
  }
  setMatrixAt(e, f) {
    f.toArray(this.instanceMatrix.array, e * 16);
  }
  setMorphAt(e, f) {
    const n = f.morphTargetInfluences, v = n.length + 1;
    this.morphTexture === null && (this.morphTexture = new jFt(new Float32Array(v * this.count), v, this.count, jN, Zz));
    const r = this.morphTexture.source.data.data;
    let z = 0;
    for (let a = 0; a < n.length; a++) z += n[a];
    const u = this.geometry.morphTargetsRelative ? 1 : 1 - z, b = v * e;
    r[b] = u, r.set(n, b + 1);
  }
  updateMorphTargets() {
  }
  dispose() {
    this.dispatchEvent({ type: "dispose" }), this.morphTexture !== null && (this.morphTexture.dispose(), this.morphTexture = null);
  }
}
const NM = new ht();
const LFt = new ht();
const OFt = new Tf();
class q5 {
  constructor(e = new ht(1, 0, 0), f = 0) {
    this.isPlane = true, this.normal = e, this.constant = f;
  }
  set(e, f) {
    return this.normal.copy(e), this.constant = f, this;
  }
  setComponents(e, f, n, v) {
    return this.normal.set(e, f, n), this.constant = v, this;
  }
  setFromNormalAndCoplanarPoint(e, f) {
    return this.normal.copy(e), this.constant = -f.dot(this.normal), this;
  }
  setFromCoplanarPoints(e, f, n) {
    const v = NM.subVectors(n, f).cross(LFt.subVectors(e, f)).normalize();
    return this.setFromNormalAndCoplanarPoint(v, e), this;
  }
  copy(e) {
    return this.normal.copy(e.normal), this.constant = e.constant, this;
  }
  normalize() {
    const e = 1 / this.normal.length();
    return this.normal.multiplyScalar(e), this.constant *= e, this;
  }
  negate() {
    return this.constant *= -1, this.normal.negate(), this;
  }
  distanceToPoint(e) {
    return this.normal.dot(e) + this.constant;
  }
  distanceToSphere(e) {
    return this.distanceToPoint(e.center) - e.radius;
  }
  projectPoint(e, f) {
    return f.copy(e).addScaledVector(this.normal, -this.distanceToPoint(e));
  }
  intersectLine(e, f) {
    const n = e.delta(NM), v = this.normal.dot(n);
    if (v === 0) return this.distanceToPoint(e.start) === 0 ? f.copy(e.start) : null;
    const r = -(e.start.dot(this.normal) + this.constant) / v;
    return r < 0 || r > 1 ? null : f.copy(e.start).addScaledVector(n, r);
  }
  intersectsLine(e) {
    const f = this.distanceToPoint(e.start), n = this.distanceToPoint(e.end);
    return f < 0 && n > 0 || n < 0 && f > 0;
  }
  intersectsBox(e) {
    return e.intersectsPlane(this);
  }
  intersectsSphere(e) {
    return e.intersectsPlane(this);
  }
  coplanarPoint(e) {
    return e.copy(this.normal).multiplyScalar(-this.constant);
  }
  applyMatrix4(e, f) {
    const n = f || OFt.getNormalMatrix(e), v = this.coplanarPoint(NM).applyMatrix4(e), r = this.normal.applyMatrix3(n).normalize();
    return this.constant = -v.dot(r), this;
  }
  translate(e) {
    return this.constant -= e.dot(this.normal), this;
  }
  equals(e) {
    return e.normal.equals(this.normal) && e.constant === this.constant;
  }
  clone() {
    return new this.constructor().copy(this);
  }
}
const hb = new $a();
const HFt = new Me(0.5, 0.5);
const V6 = new ht();
class MM {
  constructor(e = new q5(), f = new q5(), n = new q5(), v = new q5(), r = new q5(), z = new q5()) {
    this.planes = [e, f, n, v, r, z];
  }
  set(e, f, n, v, r, z) {
    const u = this.planes;
    return u[0].copy(e), u[1].copy(f), u[2].copy(n), u[3].copy(v), u[4].copy(r), u[5].copy(z), this;
  }
  copy(e) {
    const f = this.planes;
    for (let n = 0; n < 6; n++) f[n].copy(e.planes[n]);
    return this;
  }
  setFromProjectionMatrix(e, f = mz, n = false) {
    const v = this.planes, r = e.elements, z = r[0], u = r[1], b = r[2], a = r[3], P = r[4], s = r[5], d = r[6], c = r[7], l = r[8], W = r[9], X = r[10], y = r[11], m = r[12], j = r[13], x = r[14], L = r[15];
    if (v[0].setComponents(a - z, c - P, y - l, L - m).normalize(), v[1].setComponents(a + z, c + P, y + l, L + m).normalize(), v[2].setComponents(a + u, c + s, y + W, L + j).normalize(), v[3].setComponents(a - u, c - s, y - W, L - j).normalize(), n) v[4].setComponents(b, d, X, x).normalize(), v[5].setComponents(a - b, c - d, y - X, L - x).normalize();
    else if (v[4].setComponents(a - b, c - d, y - X, L - x).normalize(), f === mz) v[5].setComponents(a + b, c + d, y + X, L + x).normalize();
    else if (f === b6) v[5].setComponents(b, d, X, x).normalize();
    else throw new Error("THREE.Frustum.setFromProjectionMatrix(): Invalid coordinate system: " + f);
    return this;
  }
  intersectsObject(e) {
    if (e.boundingSphere !== void 0) e.boundingSphere === null && e.computeBoundingSphere(), hb.copy(e.boundingSphere).applyMatrix4(e.matrixWorld);
    else {
      const f = e.geometry;
      f.boundingSphere === null && f.computeBoundingSphere(), hb.copy(f.boundingSphere).applyMatrix4(e.matrixWorld);
    }
    return this.intersectsSphere(hb);
  }
  intersectsSprite(e) {
    hb.center.set(0, 0, 0);
    const f = HFt.distanceTo(e.center);
    return hb.radius = 0.7071067811865476 + f, hb.applyMatrix4(e.matrixWorld), this.intersectsSphere(hb);
  }
  intersectsSphere(e) {
    const f = this.planes, n = e.center, v = -e.radius;
    for (let r = 0; r < 6; r++) if (f[r].distanceToPoint(n) < v) return false;
    return true;
  }
  intersectsBox(e) {
    const f = this.planes;
    for (let n = 0; n < 6; n++) {
      const v = f[n];
      if (V6.x = v.normal.x > 0 ? e.max.x : e.min.x, V6.y = v.normal.y > 0 ? e.max.y : e.min.y, V6.z = v.normal.z > 0 ? e.max.z : e.min.z, v.distanceToPoint(V6) < 0) return false;
    }
    return true;
  }
  containsPoint(e) {
    const f = this.planes;
    for (let n = 0; n < 6; n++) if (f[n].distanceToPoint(e) < 0) return false;
    return true;
  }
  clone() {
    return new this.constructor().copy(this);
  }
}
class iM extends ob {
  constructor(e) {
    super(), this.isLineBasicMaterial = true, this.type = "LineBasicMaterial", this.color = new Nf(16777215), this.map = null, this.linewidth = 1, this.linecap = "round", this.linejoin = "round", this.fog = true, this.setValues(e);
  }
  copy(e) {
    return super.copy(e), this.color.copy(e.color), this.map = e.map, this.linewidth = e.linewidth, this.linecap = e.linecap, this.linejoin = e.linejoin, this.fog = e.fog, this;
  }
}
const Y6 = new ht();
const D6 = new ht();
const GK = new cn();
const Js = new W6();
const q6 = new $a();
const pM = new ht();
const VK = new ht();
class NFt extends zv {
  constructor(e = new bv(), f = new iM()) {
    super(), this.isLine = true, this.type = "Line", this.geometry = e, this.material = f, this.morphTargetDictionary = void 0, this.morphTargetInfluences = void 0, this.updateMorphTargets();
  }
  copy(e, f) {
    return super.copy(e, f), this.material = Array.isArray(e.material) ? e.material.slice() : e.material, this.geometry = e.geometry, this;
  }
  computeLineDistances() {
    const e = this.geometry;
    if (e.index === null) {
      const f = e.attributes.position, n = [0];
      for (let v = 1, r = f.count; v < r; v++) Y6.fromBufferAttribute(f, v - 1), D6.fromBufferAttribute(f, v), n[v] = n[v - 1], n[v] += Y6.distanceTo(D6);
      e.setAttribute("lineDistance", new ln(n, 1));
    } else console.warn("THREE.Line.computeLineDistances(): Computation only possible with non-indexed BufferGeometry.");
    return this;
  }
  raycast(e, f) {
    const n = this.geometry, v = this.matrixWorld, r = e.params.Line.threshold, z = n.drawRange;
    if (n.boundingSphere === null && n.computeBoundingSphere(), q6.copy(n.boundingSphere), q6.applyMatrix4(v), q6.radius += r, e.ray.intersectsSphere(q6) === false) return;
    GK.copy(v).invert(), Js.copy(e.ray).applyMatrix4(GK);
    const u = r / ((this.scale.x + this.scale.y + this.scale.z) / 3), b = u * u, a = this.isLineSegments ? 2 : 1, P = n.index, d = n.attributes.position;
    if (P !== null) {
      const c = Math.max(0, z.start), l = Math.min(P.count, z.start + z.count);
      for (let W = c, X = l - 1; W < X; W += a) {
        const y = P.getX(W), m = P.getX(W + 1), j = F6(this, e, Js, b, y, m, W);
        j && f.push(j);
      }
      if (this.isLineLoop) {
        const W = P.getX(l - 1), X = P.getX(c), y = F6(this, e, Js, b, W, X, l - 1);
        y && f.push(y);
      }
    } else {
      const c = Math.max(0, z.start), l = Math.min(d.count, z.start + z.count);
      for (let W = c, X = l - 1; W < X; W += a) {
        const y = F6(this, e, Js, b, W, W + 1, W);
        y && f.push(y);
      }
      if (this.isLineLoop) {
        const W = F6(this, e, Js, b, l - 1, c, l - 1);
        W && f.push(W);
      }
    }
  }
  updateMorphTargets() {
    const f = this.geometry.morphAttributes, n = Object.keys(f);
    if (n.length > 0) {
      const v = f[n[0]];
      if (v !== void 0) {
        this.morphTargetInfluences = [], this.morphTargetDictionary = {};
        for (let r = 0, z = v.length; r < z; r++) {
          const u = v[r].name || String(r);
          this.morphTargetInfluences.push(0), this.morphTargetDictionary[u] = r;
        }
      }
    }
  }
}
function F6(t, e, f, n, v, r, z) {
  const u = t.geometry.attributes.position;
  if (Y6.fromBufferAttribute(u, v), D6.fromBufferAttribute(u, r), f.distanceSqToSegment(Y6, D6, pM, VK) > n) return;
  pM.applyMatrix4(t.matrixWorld);
  const a = e.ray.origin.distanceTo(pM);
  if (!(a < e.near || a > e.far)) return { distance: a, point: VK.clone().applyMatrix4(t.matrixWorld), index: z, face: null, faceIndex: null, barycoord: null, object: t };
}
const YK = new ht();
const DK = new ht();
class qK extends NFt {
  constructor(e, f) {
    super(e, f), this.isLineSegments = true, this.type = "LineSegments";
  }
  computeLineDistances() {
    const e = this.geometry;
    if (e.index === null) {
      const f = e.attributes.position, n = [];
      for (let v = 0, r = f.count; v < r; v += 2) YK.fromBufferAttribute(f, v), DK.fromBufferAttribute(f, v + 1), n[v] = v === 0 ? 0 : n[v - 1], n[v + 1] = n[v] + YK.distanceTo(DK);
      e.setAttribute("lineDistance", new ln(n, 1));
    } else console.warn("THREE.LineSegments.computeLineDistances(): Computation only possible with non-indexed BufferGeometry.");
    return this;
  }
}
class TM extends Tv {
  constructor(e, f, n, v, r, z, u, b, a) {
    super(e, f, n, v, r, z, u, b, a), this.isCanvasTexture = true, this.needsUpdate = true;
  }
}
class FK extends Tv {
  constructor(e, f, n = Hb, v, r, z, u = tr, b = tr, a, P = Ms, s = 1) {
    if (P !== Ms && P !== is) throw new Error("DepthTexture format must be either THREE.DepthFormat or THREE.DepthStencilFormat");
    const d = { width: e, height: f, depth: s };
    super(d, v, r, z, u, b, P, n, a), this.isDepthTexture = true, this.flipY = false, this.generateMipmaps = false, this.compareFunction = null;
  }
  copy(e) {
    return super.copy(e), this.source = new fM(Object.assign({}, e.image)), this.compareFunction = e.compareFunction, this;
  }
  toJSON(e) {
    const f = super.toJSON(e);
    return this.compareFunction !== null && (f.compareFunction = this.compareFunction), f;
  }
}
class RK extends Tv {
  constructor(e = null) {
    super(), this.sourceTexture = e, this.isExternalTexture = true;
  }
  copy(e) {
    return super.copy(e), this.sourceTexture = e.sourceTexture, this;
  }
}
class gs extends bv {
  constructor(e = 1, f = 1, n = 4, v = 8, r = 1) {
    super(), this.type = "CapsuleGeometry", this.parameters = { radius: e, height: f, capSegments: n, radialSegments: v, heightSegments: r }, f = Math.max(0, f), n = Math.max(1, Math.floor(n)), v = Math.max(3, Math.floor(v)), r = Math.max(1, Math.floor(r));
    const z = [], u = [], b = [], a = [], P = f / 2, s = Math.PI / 2 * e, d = f, c = 2 * s + d, l = n * 2 + r, W = v + 1, X = new ht(), y = new ht();
    for (let m = 0; m <= l; m++) {
      let j = 0, x = 0, L = 0, O = 0;
      if (m <= n) {
        const H = m / n, M = H * Math.PI / 2;
        x = -P - e * Math.cos(M), L = e * Math.sin(M), O = -e * Math.cos(M), j = H * s;
      } else if (m <= n + r) {
        const H = (m - n) / r;
        x = -P + H * f, L = e, O = 0, j = s + H * d;
      } else {
        const H = (m - n - r) / n, M = H * Math.PI / 2;
        x = P + e * Math.sin(M), L = e * Math.cos(M), O = e * Math.sin(M), j = s + d + H * s;
      }
      const N = Math.max(0, Math.min(1, j / c));
      let i = 0;
      m === 0 ? i = 0.5 / v : m === l && (i = -0.5 / v);
      for (let H = 0; H <= v; H++) {
        const M = H / v, p = M * Math.PI * 2, T = Math.sin(p), w = Math.cos(p);
        y.x = -L * w, y.y = x, y.z = L * T, u.push(y.x, y.y, y.z), X.set(-L * w, O, L * T), X.normalize(), b.push(X.x, X.y, X.z), a.push(M + i, N);
      }
      if (m > 0) {
        const H = (m - 1) * W;
        for (let M = 0; M < v; M++) {
          const p = H + M, T = H + M + 1, w = m * W + M, Y = m * W + M + 1;
          z.push(p, T, w), z.push(T, Y, w);
        }
      }
    }
    this.setIndex(z), this.setAttribute("position", new ln(u, 3)), this.setAttribute("normal", new ln(b, 3)), this.setAttribute("uv", new ln(a, 2));
  }
  copy(e) {
    return super.copy(e), this.parameters = Object.assign({}, e.parameters), this;
  }
  static fromJSON(e) {
    return new gs(e.radius, e.height, e.capSegments, e.radialSegments, e.heightSegments);
  }
}
class Ss extends bv {
  constructor(e = 1, f = 1, n = 1, v = 32, r = 1, z = false, u = 0, b = Math.PI * 2) {
    super(), this.type = "CylinderGeometry", this.parameters = { radiusTop: e, radiusBottom: f, height: n, radialSegments: v, heightSegments: r, openEnded: z, thetaStart: u, thetaLength: b };
    const a = this;
    v = Math.floor(v), r = Math.floor(r);
    const P = [], s = [], d = [], c = [];
    let l = 0;
    const W = [], X = n / 2;
    let y = 0;
    m(), z === false && (e > 0 && j(true), f > 0 && j(false)), this.setIndex(P), this.setAttribute("position", new ln(s, 3)), this.setAttribute("normal", new ln(d, 3)), this.setAttribute("uv", new ln(c, 2));
    function m() {
      const x = new ht(), L = new ht();
      let O = 0;
      const N = (f - e) / n;
      for (let i = 0; i <= r; i++) {
        const H = [], M = i / r, p = M * (f - e) + e;
        for (let T = 0; T <= v; T++) {
          const w = T / v, Y = w * b + u, q = Math.sin(Y), D = Math.cos(Y);
          L.x = p * q, L.y = -M * n + X, L.z = p * D, s.push(L.x, L.y, L.z), x.set(q, N, D).normalize(), d.push(x.x, x.y, x.z), c.push(w, 1 - M), H.push(l++);
        }
        W.push(H);
      }
      for (let i = 0; i < v; i++) for (let H = 0; H < r; H++) {
        const M = W[H][i], p = W[H + 1][i], T = W[H + 1][i + 1], w = W[H][i + 1];
        (e > 0 || H !== 0) && (P.push(M, p, w), O += 3), (f > 0 || H !== r - 1) && (P.push(p, T, w), O += 3);
      }
      a.addGroup(y, O, 0), y += O;
    }
    function j(x) {
      const L = l, O = new Me(), N = new ht();
      let i = 0;
      const H = x === true ? e : f, M = x === true ? 1 : -1;
      for (let T = 1; T <= v; T++) s.push(0, X * M, 0), d.push(0, M, 0), c.push(0.5, 0.5), l++;
      const p = l;
      for (let T = 0; T <= v; T++) {
        const Y = T / v * b + u, q = Math.cos(Y), D = Math.sin(Y);
        N.x = H * D, N.y = X * M, N.z = H * q, s.push(N.x, N.y, N.z), d.push(0, M, 0), O.x = q * 0.5 + 0.5, O.y = D * 0.5 * M + 0.5, c.push(O.x, O.y), l++;
      }
      for (let T = 0; T < v; T++) {
        const w = L + T, Y = p + T;
        x === true ? P.push(Y, Y + 1, w) : P.push(Y + 1, Y, w), i += 3;
      }
      a.addGroup(y, i, x === true ? 1 : 2), y += i;
    }
  }
  copy(e) {
    return super.copy(e), this.parameters = Object.assign({}, e.parameters), this;
  }
  static fromJSON(e) {
    return new Ss(e.radiusTop, e.radiusBottom, e.height, e.radialSegments, e.heightSegments, e.openEnded, e.thetaStart, e.thetaLength);
  }
}
const R6 = new ht();
const k6 = new ht();
const oM = new ht();
const J6 = new fr();
class MFt extends bv {
  constructor(e = null, f = 1) {
    if (super(), this.type = "EdgesGeometry", this.parameters = { geometry: e, thresholdAngle: f }, e !== null) {
      const v = Math.pow(10, 4), r = Math.cos(Ka * f), z = e.getIndex(), u = e.getAttribute("position"), b = z ? z.count : u.count, a = [0, 0, 0], P = ["a", "b", "c"], s = new Array(3), d = {}, c = [];
      for (let l = 0; l < b; l += 3) {
        z ? (a[0] = z.getX(l), a[1] = z.getX(l + 1), a[2] = z.getX(l + 2)) : (a[0] = l, a[1] = l + 1, a[2] = l + 2);
        const { a: W, b: X, c: y } = J6;
        if (W.fromBufferAttribute(u, a[0]), X.fromBufferAttribute(u, a[1]), y.fromBufferAttribute(u, a[2]), J6.getNormal(oM), s[0] = `${Math.round(W.x * v)},${Math.round(W.y * v)},${Math.round(W.z * v)}`, s[1] = `${Math.round(X.x * v)},${Math.round(X.y * v)},${Math.round(X.z * v)}`, s[2] = `${Math.round(y.x * v)},${Math.round(y.y * v)},${Math.round(y.z * v)}`, !(s[0] === s[1] || s[1] === s[2] || s[2] === s[0])) for (let m = 0; m < 3; m++) {
          const j = (m + 1) % 3, x = s[m], L = s[j], O = J6[P[m]], N = J6[P[j]], i = `${x}_${L}`, H = `${L}_${x}`;
          H in d && d[H] ? (oM.dot(d[H].normal) <= r && (c.push(O.x, O.y, O.z), c.push(N.x, N.y, N.z)), d[H] = null) : i in d || (d[i] = { index0: a[m], index1: a[j], normal: oM.clone() });
        }
      }
      for (const l in d) if (d[l]) {
        const { index0: W, index1: X } = d[l];
        R6.fromBufferAttribute(u, W), k6.fromBufferAttribute(u, X), c.push(R6.x, R6.y, R6.z), c.push(k6.x, k6.y, k6.z);
      }
      this.setAttribute("position", new ln(c, 3));
    }
  }
  copy(e) {
    return super.copy(e), this.parameters = Object.assign({}, e.parameters), this;
  }
}
class Oz {
  constructor() {
    this.type = "Curve", this.arcLengthDivisions = 200, this.needsUpdate = false, this.cacheArcLengths = null;
  }
  getPoint() {
    console.warn("THREE.Curve: .getPoint() not implemented.");
  }
  getPointAt(e, f) {
    const n = this.getUtoTmapping(e);
    return this.getPoint(n, f);
  }
  getPoints(e = 5) {
    const f = [];
    for (let n = 0; n <= e; n++) f.push(this.getPoint(n / e));
    return f;
  }
  getSpacedPoints(e = 5) {
    const f = [];
    for (let n = 0; n <= e; n++) f.push(this.getPointAt(n / e));
    return f;
  }
  getLength() {
    const e = this.getLengths();
    return e[e.length - 1];
  }
  getLengths(e = this.arcLengthDivisions) {
    if (this.cacheArcLengths && this.cacheArcLengths.length === e + 1 && !this.needsUpdate) return this.cacheArcLengths;
    this.needsUpdate = false;
    const f = [];
    let n, v = this.getPoint(0), r = 0;
    f.push(0);
    for (let z = 1; z <= e; z++) n = this.getPoint(z / e), r += n.distanceTo(v), f.push(r), v = n;
    return this.cacheArcLengths = f, f;
  }
  updateArcLengths() {
    this.needsUpdate = true, this.getLengths();
  }
  getUtoTmapping(e, f = null) {
    const n = this.getLengths();
    let v = 0;
    const r = n.length;
    let z;
    f ? z = f : z = e * n[r - 1];
    let u = 0, b = r - 1, a;
    for (; u <= b; ) if (v = Math.floor(u + (b - u) / 2), a = n[v] - z, a < 0) u = v + 1;
    else if (a > 0) b = v - 1;
    else {
      b = v;
      break;
    }
    if (v = b, n[v] === z) return v / (r - 1);
    const P = n[v], d = n[v + 1] - P, c = (z - P) / d;
    return (v + c) / (r - 1);
  }
  getTangent(e, f) {
    let v = e - 1e-4, r = e + 1e-4;
    v < 0 && (v = 0), r > 1 && (r = 1);
    const z = this.getPoint(v), u = this.getPoint(r), b = f || (z.isVector2 ? new Me() : new ht());
    return b.copy(u).sub(z).normalize(), b;
  }
  getTangentAt(e, f) {
    const n = this.getUtoTmapping(e);
    return this.getTangent(n, f);
  }
  computeFrenetFrames(e, f = false) {
    const n = new ht(), v = [], r = [], z = [], u = new ht(), b = new cn();
    for (let c = 0; c <= e; c++) {
      const l = c / e;
      v[c] = this.getTangentAt(l, new ht());
    }
    r[0] = new ht(), z[0] = new ht();
    let a = Number.MAX_VALUE;
    const P = Math.abs(v[0].x), s = Math.abs(v[0].y), d = Math.abs(v[0].z);
    P <= a && (a = P, n.set(1, 0, 0)), s <= a && (a = s, n.set(0, 1, 0)), d <= a && n.set(0, 0, 1), u.crossVectors(v[0], n).normalize(), r[0].crossVectors(v[0], u), z[0].crossVectors(v[0], r[0]);
    for (let c = 1; c <= e; c++) {
      if (r[c] = r[c - 1].clone(), z[c] = z[c - 1].clone(), u.crossVectors(v[c - 1], v[c]), u.length() > Number.EPSILON) {
        u.normalize();
        const l = Math.acos(pf(v[c - 1].dot(v[c]), -1, 1));
        r[c].applyMatrix4(b.makeRotationAxis(u, l));
      }
      z[c].crossVectors(v[c], r[c]);
    }
    if (f === true) {
      let c = Math.acos(pf(r[0].dot(r[e]), -1, 1));
      c /= e, v[0].dot(u.crossVectors(r[0], r[e])) > 0 && (c = -c);
      for (let l = 1; l <= e; l++) r[l].applyMatrix4(b.makeRotationAxis(v[l], c * l)), z[l].crossVectors(v[l], r[l]);
    }
    return { tangents: v, normals: r, binormals: z };
  }
  clone() {
    return new this.constructor().copy(this);
  }
  copy(e) {
    return this.arcLengthDivisions = e.arcLengthDivisions, this;
  }
  toJSON() {
    const e = { metadata: { version: 4.7, type: "Curve", generator: "Curve.toJSON" } };
    return e.arcLengthDivisions = this.arcLengthDivisions, e.type = this.type, e;
  }
  fromJSON(e) {
    return this.arcLengthDivisions = e.arcLengthDivisions, this;
  }
}
class wM extends Oz {
  constructor(e = 0, f = 0, n = 1, v = 1, r = 0, z = Math.PI * 2, u = false, b = 0) {
    super(), this.isEllipseCurve = true, this.type = "EllipseCurve", this.aX = e, this.aY = f, this.xRadius = n, this.yRadius = v, this.aStartAngle = r, this.aEndAngle = z, this.aClockwise = u, this.aRotation = b;
  }
  getPoint(e, f = new Me()) {
    const n = f, v = Math.PI * 2;
    let r = this.aEndAngle - this.aStartAngle;
    const z = Math.abs(r) < Number.EPSILON;
    for (; r < 0; ) r += v;
    for (; r > v; ) r -= v;
    r < Number.EPSILON && (z ? r = 0 : r = v), this.aClockwise === true && !z && (r === v ? r = -v : r = r - v);
    const u = this.aStartAngle + e * r;
    let b = this.aX + this.xRadius * Math.cos(u), a = this.aY + this.yRadius * Math.sin(u);
    if (this.aRotation !== 0) {
      const P = Math.cos(this.aRotation), s = Math.sin(this.aRotation), d = b - this.aX, c = a - this.aY;
      b = d * P - c * s + this.aX, a = d * s + c * P + this.aY;
    }
    return n.set(b, a);
  }
  copy(e) {
    return super.copy(e), this.aX = e.aX, this.aY = e.aY, this.xRadius = e.xRadius, this.yRadius = e.yRadius, this.aStartAngle = e.aStartAngle, this.aEndAngle = e.aEndAngle, this.aClockwise = e.aClockwise, this.aRotation = e.aRotation, this;
  }
  toJSON() {
    const e = super.toJSON();
    return e.aX = this.aX, e.aY = this.aY, e.xRadius = this.xRadius, e.yRadius = this.yRadius, e.aStartAngle = this.aStartAngle, e.aEndAngle = this.aEndAngle, e.aClockwise = this.aClockwise, e.aRotation = this.aRotation, e;
  }
  fromJSON(e) {
    return super.fromJSON(e), this.aX = e.aX, this.aY = e.aY, this.xRadius = e.xRadius, this.yRadius = e.yRadius, this.aStartAngle = e.aStartAngle, this.aEndAngle = e.aEndAngle, this.aClockwise = e.aClockwise, this.aRotation = e.aRotation, this;
  }
}
class iFt extends wM {
  constructor(e, f, n, v, r, z) {
    super(e, f, n, n, v, r, z), this.isArcCurve = true, this.type = "ArcCurve";
  }
}
function hM() {
  let t = 0, e = 0, f = 0, n = 0;
  function v(r, z, u, b) {
    t = r, e = u, f = -3 * r + 3 * z - 2 * u - b, n = 2 * r - 2 * z + u + b;
  }
  return { initCatmullRom: function(r, z, u, b, a) {
    v(z, u, a * (u - r), a * (b - z));
  }, initNonuniformCatmullRom: function(r, z, u, b, a, P, s) {
    let d = (z - r) / a - (u - r) / (a + P) + (u - z) / P, c = (u - z) / P - (b - z) / (P + s) + (b - u) / s;
    d *= P, c *= P, v(z, u, d, c);
  }, calc: function(r) {
    const z = r * r, u = z * r;
    return t + e * r + f * z + n * u;
  } };
}
const g6 = new ht();
const GM = new hM();
const VM = new hM();
const YM = new hM();
class DM extends Oz {
  constructor(e = [], f = false, n = "centripetal", v = 0.5) {
    super(), this.isCatmullRomCurve3 = true, this.type = "CatmullRomCurve3", this.points = e, this.closed = f, this.curveType = n, this.tension = v;
  }
  getPoint(e, f = new ht()) {
    const n = f, v = this.points, r = v.length, z = (r - (this.closed ? 0 : 1)) * e;
    let u = Math.floor(z), b = z - u;
    this.closed ? u += u > 0 ? 0 : (Math.floor(Math.abs(u) / r) + 1) * r : b === 0 && u === r - 1 && (u = r - 2, b = 1);
    let a, P;
    this.closed || u > 0 ? a = v[(u - 1) % r] : (g6.subVectors(v[0], v[1]).add(v[0]), a = g6);
    const s = v[u % r], d = v[(u + 1) % r];
    if (this.closed || u + 2 < r ? P = v[(u + 2) % r] : (g6.subVectors(v[r - 1], v[r - 2]).add(v[r - 1]), P = g6), this.curveType === "centripetal" || this.curveType === "chordal") {
      const c = this.curveType === "chordal" ? 0.5 : 0.25;
      let l = Math.pow(a.distanceToSquared(s), c), W = Math.pow(s.distanceToSquared(d), c), X = Math.pow(d.distanceToSquared(P), c);
      W < 1e-4 && (W = 1), l < 1e-4 && (l = W), X < 1e-4 && (X = W), GM.initNonuniformCatmullRom(a.x, s.x, d.x, P.x, l, W, X), VM.initNonuniformCatmullRom(a.y, s.y, d.y, P.y, l, W, X), YM.initNonuniformCatmullRom(a.z, s.z, d.z, P.z, l, W, X);
    } else this.curveType === "catmullrom" && (GM.initCatmullRom(a.x, s.x, d.x, P.x, this.tension), VM.initCatmullRom(a.y, s.y, d.y, P.y, this.tension), YM.initCatmullRom(a.z, s.z, d.z, P.z, this.tension));
    return n.set(GM.calc(b), VM.calc(b), YM.calc(b)), n;
  }
  copy(e) {
    super.copy(e), this.points = [];
    for (let f = 0, n = e.points.length; f < n; f++) {
      const v = e.points[f];
      this.points.push(v.clone());
    }
    return this.closed = e.closed, this.curveType = e.curveType, this.tension = e.tension, this;
  }
  toJSON() {
    const e = super.toJSON();
    e.points = [];
    for (let f = 0, n = this.points.length; f < n; f++) {
      const v = this.points[f];
      e.points.push(v.toArray());
    }
    return e.closed = this.closed, e.curveType = this.curveType, e.tension = this.tension, e;
  }
  fromJSON(e) {
    super.fromJSON(e), this.points = [];
    for (let f = 0, n = e.points.length; f < n; f++) {
      const v = e.points[f];
      this.points.push(new ht().fromArray(v));
    }
    return this.closed = e.closed, this.curveType = e.curveType, this.tension = e.tension, this;
  }
}
function kK(t, e, f, n, v) {
  const r = (n - e) * 0.5, z = (v - f) * 0.5, u = t * t, b = t * u;
  return (2 * f - 2 * n + r + z) * b + (-3 * f + 3 * n - 2 * r - z) * u + r * t + f;
}
function pFt(t, e) {
  const f = 1 - t;
  return f * f * e;
}
function TFt(t, e) {
  return 2 * (1 - t) * t * e;
}
function oFt(t, e) {
  return t * t * e;
}
function Bs(t, e, f, n) {
  return pFt(t, e) + TFt(t, f) + oFt(t, n);
}
function wFt(t, e) {
  const f = 1 - t;
  return f * f * f * e;
}
function hFt(t, e) {
  const f = 1 - t;
  return 3 * f * f * t * e;
}
function GFt(t, e) {
  return 3 * (1 - t) * t * t * e;
}
function VFt(t, e) {
  return t * t * t * e;
}
function Us(t, e, f, n, v) {
  return wFt(t, e) + hFt(t, f) + GFt(t, n) + VFt(t, v);
}
class JK extends Oz {
  constructor(e = new Me(), f = new Me(), n = new Me(), v = new Me()) {
    super(), this.isCubicBezierCurve = true, this.type = "CubicBezierCurve", this.v0 = e, this.v1 = f, this.v2 = n, this.v3 = v;
  }
  getPoint(e, f = new Me()) {
    const n = f, v = this.v0, r = this.v1, z = this.v2, u = this.v3;
    return n.set(Us(e, v.x, r.x, z.x, u.x), Us(e, v.y, r.y, z.y, u.y)), n;
  }
  copy(e) {
    return super.copy(e), this.v0.copy(e.v0), this.v1.copy(e.v1), this.v2.copy(e.v2), this.v3.copy(e.v3), this;
  }
  toJSON() {
    const e = super.toJSON();
    return e.v0 = this.v0.toArray(), e.v1 = this.v1.toArray(), e.v2 = this.v2.toArray(), e.v3 = this.v3.toArray(), e;
  }
  fromJSON(e) {
    return super.fromJSON(e), this.v0.fromArray(e.v0), this.v1.fromArray(e.v1), this.v2.fromArray(e.v2), this.v3.fromArray(e.v3), this;
  }
}
class YFt extends Oz {
  constructor(e = new ht(), f = new ht(), n = new ht(), v = new ht()) {
    super(), this.isCubicBezierCurve3 = true, this.type = "CubicBezierCurve3", this.v0 = e, this.v1 = f, this.v2 = n, this.v3 = v;
  }
  getPoint(e, f = new ht()) {
    const n = f, v = this.v0, r = this.v1, z = this.v2, u = this.v3;
    return n.set(Us(e, v.x, r.x, z.x, u.x), Us(e, v.y, r.y, z.y, u.y), Us(e, v.z, r.z, z.z, u.z)), n;
  }
  copy(e) {
    return super.copy(e), this.v0.copy(e.v0), this.v1.copy(e.v1), this.v2.copy(e.v2), this.v3.copy(e.v3), this;
  }
  toJSON() {
    const e = super.toJSON();
    return e.v0 = this.v0.toArray(), e.v1 = this.v1.toArray(), e.v2 = this.v2.toArray(), e.v3 = this.v3.toArray(), e;
  }
  fromJSON(e) {
    return super.fromJSON(e), this.v0.fromArray(e.v0), this.v1.fromArray(e.v1), this.v2.fromArray(e.v2), this.v3.fromArray(e.v3), this;
  }
}
class gK extends Oz {
  constructor(e = new Me(), f = new Me()) {
    super(), this.isLineCurve = true, this.type = "LineCurve", this.v1 = e, this.v2 = f;
  }
  getPoint(e, f = new Me()) {
    const n = f;
    return e === 1 ? n.copy(this.v2) : (n.copy(this.v2).sub(this.v1), n.multiplyScalar(e).add(this.v1)), n;
  }
  getPointAt(e, f) {
    return this.getPoint(e, f);
  }
  getTangent(e, f = new Me()) {
    return f.subVectors(this.v2, this.v1).normalize();
  }
  getTangentAt(e, f) {
    return this.getTangent(e, f);
  }
  copy(e) {
    return super.copy(e), this.v1.copy(e.v1), this.v2.copy(e.v2), this;
  }
  toJSON() {
    const e = super.toJSON();
    return e.v1 = this.v1.toArray(), e.v2 = this.v2.toArray(), e;
  }
  fromJSON(e) {
    return super.fromJSON(e), this.v1.fromArray(e.v1), this.v2.fromArray(e.v2), this;
  }
}
class DFt extends Oz {
  constructor(e = new ht(), f = new ht()) {
    super(), this.isLineCurve3 = true, this.type = "LineCurve3", this.v1 = e, this.v2 = f;
  }
  getPoint(e, f = new ht()) {
    const n = f;
    return e === 1 ? n.copy(this.v2) : (n.copy(this.v2).sub(this.v1), n.multiplyScalar(e).add(this.v1)), n;
  }
  getPointAt(e, f) {
    return this.getPoint(e, f);
  }
  getTangent(e, f = new ht()) {
    return f.subVectors(this.v2, this.v1).normalize();
  }
  getTangentAt(e, f) {
    return this.getTangent(e, f);
  }
  copy(e) {
    return super.copy(e), this.v1.copy(e.v1), this.v2.copy(e.v2), this;
  }
  toJSON() {
    const e = super.toJSON();
    return e.v1 = this.v1.toArray(), e.v2 = this.v2.toArray(), e;
  }
  fromJSON(e) {
    return super.fromJSON(e), this.v1.fromArray(e.v1), this.v2.fromArray(e.v2), this;
  }
}
class SK extends Oz {
  constructor(e = new Me(), f = new Me(), n = new Me()) {
    super(), this.isQuadraticBezierCurve = true, this.type = "QuadraticBezierCurve", this.v0 = e, this.v1 = f, this.v2 = n;
  }
  getPoint(e, f = new Me()) {
    const n = f, v = this.v0, r = this.v1, z = this.v2;
    return n.set(Bs(e, v.x, r.x, z.x), Bs(e, v.y, r.y, z.y)), n;
  }
  copy(e) {
    return super.copy(e), this.v0.copy(e.v0), this.v1.copy(e.v1), this.v2.copy(e.v2), this;
  }
  toJSON() {
    const e = super.toJSON();
    return e.v0 = this.v0.toArray(), e.v1 = this.v1.toArray(), e.v2 = this.v2.toArray(), e;
  }
  fromJSON(e) {
    return super.fromJSON(e), this.v0.fromArray(e.v0), this.v1.fromArray(e.v1), this.v2.fromArray(e.v2), this;
  }
}
class qFt extends Oz {
  constructor(e = new ht(), f = new ht(), n = new ht()) {
    super(), this.isQuadraticBezierCurve3 = true, this.type = "QuadraticBezierCurve3", this.v0 = e, this.v1 = f, this.v2 = n;
  }
  getPoint(e, f = new ht()) {
    const n = f, v = this.v0, r = this.v1, z = this.v2;
    return n.set(Bs(e, v.x, r.x, z.x), Bs(e, v.y, r.y, z.y), Bs(e, v.z, r.z, z.z)), n;
  }
  copy(e) {
    return super.copy(e), this.v0.copy(e.v0), this.v1.copy(e.v1), this.v2.copy(e.v2), this;
  }
  toJSON() {
    const e = super.toJSON();
    return e.v0 = this.v0.toArray(), e.v1 = this.v1.toArray(), e.v2 = this.v2.toArray(), e;
  }
  fromJSON(e) {
    return super.fromJSON(e), this.v0.fromArray(e.v0), this.v1.fromArray(e.v1), this.v2.fromArray(e.v2), this;
  }
}
class BK extends Oz {
  constructor(e = []) {
    super(), this.isSplineCurve = true, this.type = "SplineCurve", this.points = e;
  }
  getPoint(e, f = new Me()) {
    const n = f, v = this.points, r = (v.length - 1) * e, z = Math.floor(r), u = r - z, b = v[z === 0 ? z : z - 1], a = v[z], P = v[z > v.length - 2 ? v.length - 1 : z + 1], s = v[z > v.length - 3 ? v.length - 1 : z + 2];
    return n.set(kK(u, b.x, a.x, P.x, s.x), kK(u, b.y, a.y, P.y, s.y)), n;
  }
  copy(e) {
    super.copy(e), this.points = [];
    for (let f = 0, n = e.points.length; f < n; f++) {
      const v = e.points[f];
      this.points.push(v.clone());
    }
    return this;
  }
  toJSON() {
    const e = super.toJSON();
    e.points = [];
    for (let f = 0, n = this.points.length; f < n; f++) {
      const v = this.points[f];
      e.points.push(v.toArray());
    }
    return e;
  }
  fromJSON(e) {
    super.fromJSON(e), this.points = [];
    for (let f = 0, n = e.points.length; f < n; f++) {
      const v = e.points[f];
      this.points.push(new Me().fromArray(v));
    }
    return this;
  }
}
var qM = Object.freeze({ __proto__: null, ArcCurve: iFt, CatmullRomCurve3: DM, CubicBezierCurve: JK, CubicBezierCurve3: YFt, EllipseCurve: wM, LineCurve: gK, LineCurve3: DFt, QuadraticBezierCurve: SK, QuadraticBezierCurve3: qFt, SplineCurve: BK });
class FFt extends Oz {
  constructor() {
    super(), this.type = "CurvePath", this.curves = [], this.autoClose = false;
  }
  add(e) {
    this.curves.push(e);
  }
  closePath() {
    const e = this.curves[0].getPoint(0), f = this.curves[this.curves.length - 1].getPoint(1);
    if (!e.equals(f)) {
      const n = e.isVector2 === true ? "LineCurve" : "LineCurve3";
      this.curves.push(new qM[n](f, e));
    }
    return this;
  }
  getPoint(e, f) {
    const n = e * this.getLength(), v = this.getCurveLengths();
    let r = 0;
    for (; r < v.length; ) {
      if (v[r] >= n) {
        const z = v[r] - n, u = this.curves[r], b = u.getLength(), a = b === 0 ? 0 : 1 - z / b;
        return u.getPointAt(a, f);
      }
      r++;
    }
    return null;
  }
  getLength() {
    const e = this.getCurveLengths();
    return e[e.length - 1];
  }
  updateArcLengths() {
    this.needsUpdate = true, this.cacheLengths = null, this.getCurveLengths();
  }
  getCurveLengths() {
    if (this.cacheLengths && this.cacheLengths.length === this.curves.length) return this.cacheLengths;
    const e = [];
    let f = 0;
    for (let n = 0, v = this.curves.length; n < v; n++) f += this.curves[n].getLength(), e.push(f);
    return this.cacheLengths = e, e;
  }
  getSpacedPoints(e = 40) {
    const f = [];
    for (let n = 0; n <= e; n++) f.push(this.getPoint(n / e));
    return this.autoClose && f.push(f[0]), f;
  }
  getPoints(e = 12) {
    const f = [];
    let n;
    for (let v = 0, r = this.curves; v < r.length; v++) {
      const z = r[v], u = z.isEllipseCurve ? e * 2 : z.isLineCurve || z.isLineCurve3 ? 1 : z.isSplineCurve ? e * z.points.length : e, b = z.getPoints(u);
      for (let a = 0; a < b.length; a++) {
        const P = b[a];
        n && n.equals(P) || (f.push(P), n = P);
      }
    }
    return this.autoClose && f.length > 1 && !f[f.length - 1].equals(f[0]) && f.push(f[0]), f;
  }
  copy(e) {
    super.copy(e), this.curves = [];
    for (let f = 0, n = e.curves.length; f < n; f++) {
      const v = e.curves[f];
      this.curves.push(v.clone());
    }
    return this.autoClose = e.autoClose, this;
  }
  toJSON() {
    const e = super.toJSON();
    e.autoClose = this.autoClose, e.curves = [];
    for (let f = 0, n = this.curves.length; f < n; f++) {
      const v = this.curves[f];
      e.curves.push(v.toJSON());
    }
    return e;
  }
  fromJSON(e) {
    super.fromJSON(e), this.autoClose = e.autoClose, this.curves = [];
    for (let f = 0, n = e.curves.length; f < n; f++) {
      const v = e.curves[f];
      this.curves.push(new qM[v.type]().fromJSON(v));
    }
    return this;
  }
}
class UK extends FFt {
  constructor(e) {
    super(), this.type = "Path", this.currentPoint = new Me(), e && this.setFromPoints(e);
  }
  setFromPoints(e) {
    this.moveTo(e[0].x, e[0].y);
    for (let f = 1, n = e.length; f < n; f++) this.lineTo(e[f].x, e[f].y);
    return this;
  }
  moveTo(e, f) {
    return this.currentPoint.set(e, f), this;
  }
  lineTo(e, f) {
    const n = new gK(this.currentPoint.clone(), new Me(e, f));
    return this.curves.push(n), this.currentPoint.set(e, f), this;
  }
  quadraticCurveTo(e, f, n, v) {
    const r = new SK(this.currentPoint.clone(), new Me(e, f), new Me(n, v));
    return this.curves.push(r), this.currentPoint.set(n, v), this;
  }
  bezierCurveTo(e, f, n, v, r, z) {
    const u = new JK(this.currentPoint.clone(), new Me(e, f), new Me(n, v), new Me(r, z));
    return this.curves.push(u), this.currentPoint.set(r, z), this;
  }
  splineThru(e) {
    const f = [this.currentPoint.clone()].concat(e), n = new BK(f);
    return this.curves.push(n), this.currentPoint.copy(e[e.length - 1]), this;
  }
  arc(e, f, n, v, r, z) {
    const u = this.currentPoint.x, b = this.currentPoint.y;
    return this.absarc(e + u, f + b, n, v, r, z), this;
  }
  absarc(e, f, n, v, r, z) {
    return this.absellipse(e, f, n, n, v, r, z), this;
  }
  ellipse(e, f, n, v, r, z, u, b) {
    const a = this.currentPoint.x, P = this.currentPoint.y;
    return this.absellipse(e + a, f + P, n, v, r, z, u, b), this;
  }
  absellipse(e, f, n, v, r, z, u, b) {
    const a = new wM(e, f, n, v, r, z, u, b);
    if (this.curves.length > 0) {
      const s = a.getPoint(0);
      s.equals(this.currentPoint) || this.lineTo(s.x, s.y);
    }
    this.curves.push(a);
    const P = a.getPoint(1);
    return this.currentPoint.copy(P), this;
  }
  copy(e) {
    return super.copy(e), this.currentPoint.copy(e.currentPoint), this;
  }
  toJSON() {
    const e = super.toJSON();
    return e.currentPoint = this.currentPoint.toArray(), e;
  }
  fromJSON(e) {
    return super.fromJSON(e), this.currentPoint.fromArray(e.currentPoint), this;
  }
}
class Is extends UK {
  constructor(e) {
    super(e), this.uuid = jz(), this.type = "Shape", this.holes = [];
  }
  getPointsHoles(e) {
    const f = [];
    for (let n = 0, v = this.holes.length; n < v; n++) f[n] = this.holes[n].getPoints(e);
    return f;
  }
  extractPoints(e) {
    return { shape: this.getPoints(e), holes: this.getPointsHoles(e) };
  }
  copy(e) {
    super.copy(e), this.holes = [];
    for (let f = 0, n = e.holes.length; f < n; f++) {
      const v = e.holes[f];
      this.holes.push(v.clone());
    }
    return this;
  }
  toJSON() {
    const e = super.toJSON();
    e.uuid = this.uuid, e.holes = [];
    for (let f = 0, n = this.holes.length; f < n; f++) {
      const v = this.holes[f];
      e.holes.push(v.toJSON());
    }
    return e;
  }
  fromJSON(e) {
    super.fromJSON(e), this.uuid = e.uuid, this.holes = [];
    for (let f = 0, n = e.holes.length; f < n; f++) {
      const v = e.holes[f];
      this.holes.push(new UK().fromJSON(v));
    }
    return this;
  }
}
function RFt(t, e, f = 2) {
  const n = e && e.length, v = n ? e[0] * f : t.length;
  let r = IK(t, 0, v, f, true);
  const z = [];
  if (!r || r.next === r.prev) return z;
  let u, b, a;
  if (n && (r = BFt(t, e, r, f)), t.length > 80 * f) {
    u = 1 / 0, b = 1 / 0;
    let P = -1 / 0, s = -1 / 0;
    for (let d = f; d < v; d += f) {
      const c = t[d], l = t[d + 1];
      c < u && (u = c), l < b && (b = l), c > P && (P = c), l > s && (s = l);
    }
    a = Math.max(P - u, s - b), a = a !== 0 ? 32767 / a : 0;
  }
  return Ks(r, z, f, u, b, a, 0), z;
}
function IK(t, e, f, n, v) {
  let r;
  if (v === eRt(t, e, f, n) > 0) for (let z = e; z < f; z += n) r = AK(z / n | 0, t[z], t[z + 1], r);
  else for (let z = f - n; z >= e; z -= n) r = AK(z / n | 0, t[z], t[z + 1], r);
  return r && l1(r, r.next) && (As(r), r = r.next), r;
}
function Gb(t, e) {
  if (!t) return t;
  e || (e = t);
  let f = t, n;
  do
    if (n = false, !f.steiner && (l1(f, f.next) || Rn(f.prev, f, f.next) === 0)) {
      if (As(f), f = e = f.prev, f === f.next) break;
      n = true;
    } else f = f.next;
  while (n || f !== e);
  return e;
}
function Ks(t, e, f, n, v, r, z) {
  if (!t) return;
  !z && r && CFt(t, n, v, r);
  let u = t;
  for (; t.prev !== t.next; ) {
    const b = t.prev, a = t.next;
    if (r ? JFt(t, n, v, r) : kFt(t)) {
      e.push(b.i, t.i, a.i), As(t), t = a.next, u = a.next;
      continue;
    }
    if (t = a, t === u) {
      z ? z === 1 ? (t = gFt(Gb(t), e), Ks(t, e, f, n, v, r, 2)) : z === 2 && SFt(t, e, f, n, v, r) : Ks(Gb(t), e, f, n, v, r, 1);
      break;
    }
  }
}
function kFt(t) {
  const e = t.prev, f = t, n = t.next;
  if (Rn(e, f, n) >= 0) return false;
  const v = e.x, r = f.x, z = n.x, u = e.y, b = f.y, a = n.y, P = Math.min(v, r, z), s = Math.min(u, b, a), d = Math.max(v, r, z), c = Math.max(u, b, a);
  let l = n.next;
  for (; l !== e; ) {
    if (l.x >= P && l.x <= d && l.y >= s && l.y <= c && Es(v, u, r, b, z, a, l.x, l.y) && Rn(l.prev, l, l.next) >= 0) return false;
    l = l.next;
  }
  return true;
}
function JFt(t, e, f, n) {
  const v = t.prev, r = t, z = t.next;
  if (Rn(v, r, z) >= 0) return false;
  const u = v.x, b = r.x, a = z.x, P = v.y, s = r.y, d = z.y, c = Math.min(u, b, a), l = Math.min(P, s, d), W = Math.max(u, b, a), X = Math.max(P, s, d), y = FM(c, l, e, f, n), m = FM(W, X, e, f, n);
  let j = t.prevZ, x = t.nextZ;
  for (; j && j.z >= y && x && x.z <= m; ) {
    if (j.x >= c && j.x <= W && j.y >= l && j.y <= X && j !== v && j !== z && Es(u, P, b, s, a, d, j.x, j.y) && Rn(j.prev, j, j.next) >= 0 || (j = j.prevZ, x.x >= c && x.x <= W && x.y >= l && x.y <= X && x !== v && x !== z && Es(u, P, b, s, a, d, x.x, x.y) && Rn(x.prev, x, x.next) >= 0)) return false;
    x = x.nextZ;
  }
  for (; j && j.z >= y; ) {
    if (j.x >= c && j.x <= W && j.y >= l && j.y <= X && j !== v && j !== z && Es(u, P, b, s, a, d, j.x, j.y) && Rn(j.prev, j, j.next) >= 0) return false;
    j = j.prevZ;
  }
  for (; x && x.z <= m; ) {
    if (x.x >= c && x.x <= W && x.y >= l && x.y <= X && x !== v && x !== z && Es(u, P, b, s, a, d, x.x, x.y) && Rn(x.prev, x, x.next) >= 0) return false;
    x = x.nextZ;
  }
  return true;
}
function gFt(t, e) {
  let f = t;
  do {
    const n = f.prev, v = f.next.next;
    !l1(n, v) && EK(n, f, f.next, v) && Cs(n, v) && Cs(v, n) && (e.push(n.i, f.i, v.i), As(f), As(f.next), f = t = v), f = f.next;
  } while (f !== t);
  return Gb(f);
}
function SFt(t, e, f, n, v, r) {
  let z = t;
  do {
    let u = z.next.next;
    for (; u !== z.prev; ) {
      if (z.i !== u.i && _Ft(z, u)) {
        let b = CK(z, u);
        z = Gb(z, z.next), b = Gb(b, b.next), Ks(z, e, f, n, v, r, 0), Ks(b, e, f, n, v, r, 0);
        return;
      }
      u = u.next;
    }
    z = z.next;
  } while (z !== t);
}
function BFt(t, e, f, n) {
  const v = [];
  for (let r = 0, z = e.length; r < z; r++) {
    const u = e[r] * n, b = r < z - 1 ? e[r + 1] * n : t.length, a = IK(t, u, b, n, false);
    a === a.next && (a.steiner = true), v.push(QFt(a));
  }
  v.sort(UFt);
  for (let r = 0; r < v.length; r++) f = IFt(v[r], f);
  return f;
}
function UFt(t, e) {
  let f = t.x - e.x;
  if (f === 0 && (f = t.y - e.y, f === 0)) {
    const n = (t.next.y - t.y) / (t.next.x - t.x), v = (e.next.y - e.y) / (e.next.x - e.x);
    f = n - v;
  }
  return f;
}
function IFt(t, e) {
  const f = KFt(t, e);
  if (!f) return e;
  const n = CK(f, t);
  return Gb(n, n.next), Gb(f, f.next);
}
function KFt(t, e) {
  let f = e;
  const n = t.x, v = t.y;
  let r = -1 / 0, z;
  if (l1(t, f)) return f;
  do {
    if (l1(t, f.next)) return f.next;
    if (v <= f.y && v >= f.next.y && f.next.y !== f.y) {
      const s = f.x + (v - f.y) * (f.next.x - f.x) / (f.next.y - f.y);
      if (s <= n && s > r && (r = s, z = f.x < f.next.x ? f : f.next, s === n)) return z;
    }
    f = f.next;
  } while (f !== e);
  if (!z) return null;
  const u = z, b = z.x, a = z.y;
  let P = 1 / 0;
  f = z;
  do {
    if (n >= f.x && f.x >= b && n !== f.x && KK(v < a ? n : r, v, b, a, v < a ? r : n, v, f.x, f.y)) {
      const s = Math.abs(v - f.y) / (n - f.x);
      Cs(f, t) && (s < P || s === P && (f.x > z.x || f.x === z.x && EFt(z, f))) && (z = f, P = s);
    }
    f = f.next;
  } while (f !== u);
  return z;
}
function EFt(t, e) {
  return Rn(t.prev, t, e.prev) < 0 && Rn(e.next, t, t.next) < 0;
}
function CFt(t, e, f, n) {
  let v = t;
  do
    v.z === 0 && (v.z = FM(v.x, v.y, e, f, n)), v.prevZ = v.prev, v.nextZ = v.next, v = v.next;
  while (v !== t);
  v.prevZ.nextZ = null, v.prevZ = null, AFt(v);
}
function AFt(t) {
  let e, f = 1;
  do {
    let n = t, v;
    t = null;
    let r = null;
    for (e = 0; n; ) {
      e++;
      let z = n, u = 0;
      for (let a = 0; a < f && (u++, z = z.nextZ, !!z); a++) ;
      let b = f;
      for (; u > 0 || b > 0 && z; ) u !== 0 && (b === 0 || !z || n.z <= z.z) ? (v = n, n = n.nextZ, u--) : (v = z, z = z.nextZ, b--), r ? r.nextZ = v : t = v, v.prevZ = r, r = v;
      n = z;
    }
    r.nextZ = null, f *= 2;
  } while (e > 1);
  return t;
}
function FM(t, e, f, n, v) {
  return t = (t - f) * v | 0, e = (e - n) * v | 0, t = (t | t << 8) & 16711935, t = (t | t << 4) & 252645135, t = (t | t << 2) & 858993459, t = (t | t << 1) & 1431655765, e = (e | e << 8) & 16711935, e = (e | e << 4) & 252645135, e = (e | e << 2) & 858993459, e = (e | e << 1) & 1431655765, t | e << 1;
}
function QFt(t) {
  let e = t, f = t;
  do
    (e.x < f.x || e.x === f.x && e.y < f.y) && (f = e), e = e.next;
  while (e !== t);
  return f;
}
function KK(t, e, f, n, v, r, z, u) {
  return (v - z) * (e - u) >= (t - z) * (r - u) && (t - z) * (n - u) >= (f - z) * (e - u) && (f - z) * (r - u) >= (v - z) * (n - u);
}
function Es(t, e, f, n, v, r, z, u) {
  return !(t === z && e === u) && KK(t, e, f, n, v, r, z, u);
}
function _Ft(t, e) {
  return t.next.i !== e.i && t.prev.i !== e.i && !$Ft(t, e) && (Cs(t, e) && Cs(e, t) && tRt(t, e) && (Rn(t.prev, t, e.prev) || Rn(t, e.prev, e)) || l1(t, e) && Rn(t.prev, t, t.next) > 0 && Rn(e.prev, e, e.next) > 0);
}
function Rn(t, e, f) {
  return (e.y - t.y) * (f.x - e.x) - (e.x - t.x) * (f.y - e.y);
}
function l1(t, e) {
  return t.x === e.x && t.y === e.y;
}
function EK(t, e, f, n) {
  const v = B6(Rn(t, e, f)), r = B6(Rn(t, e, n)), z = B6(Rn(f, n, t)), u = B6(Rn(f, n, e));
  return !!(v !== r && z !== u || v === 0 && S6(t, f, e) || r === 0 && S6(t, n, e) || z === 0 && S6(f, t, n) || u === 0 && S6(f, e, n));
}
function S6(t, e, f) {
  return e.x <= Math.max(t.x, f.x) && e.x >= Math.min(t.x, f.x) && e.y <= Math.max(t.y, f.y) && e.y >= Math.min(t.y, f.y);
}
function B6(t) {
  return t > 0 ? 1 : t < 0 ? -1 : 0;
}
function $Ft(t, e) {
  let f = t;
  do {
    if (f.i !== t.i && f.next.i !== t.i && f.i !== e.i && f.next.i !== e.i && EK(f, f.next, t, e)) return true;
    f = f.next;
  } while (f !== t);
  return false;
}
function Cs(t, e) {
  return Rn(t.prev, t, t.next) < 0 ? Rn(t, e, t.next) >= 0 && Rn(t, t.prev, e) >= 0 : Rn(t, e, t.prev) < 0 || Rn(t, t.next, e) < 0;
}
function tRt(t, e) {
  let f = t, n = false;
  const v = (t.x + e.x) / 2, r = (t.y + e.y) / 2;
  do
    f.y > r != f.next.y > r && f.next.y !== f.y && v < (f.next.x - f.x) * (r - f.y) / (f.next.y - f.y) + f.x && (n = !n), f = f.next;
  while (f !== t);
  return n;
}
function CK(t, e) {
  const f = RM(t.i, t.x, t.y), n = RM(e.i, e.x, e.y), v = t.next, r = e.prev;
  return t.next = e, e.prev = t, f.next = v, v.prev = f, n.next = f, f.prev = n, r.next = n, n.prev = r, n;
}
function AK(t, e, f, n) {
  const v = RM(t, e, f);
  return n ? (v.next = n.next, v.prev = n, n.next.prev = v, n.next = v) : (v.prev = v, v.next = v), v;
}
function As(t) {
  t.next.prev = t.prev, t.prev.next = t.next, t.prevZ && (t.prevZ.nextZ = t.nextZ), t.nextZ && (t.nextZ.prevZ = t.prevZ);
}
function RM(t, e, f) {
  return { i: t, x: e, y: f, prev: null, next: null, z: 0, prevZ: null, nextZ: null, steiner: false };
}
function eRt(t, e, f, n) {
  let v = 0;
  for (let r = e, z = f - n; r < f; r += n) v += (t[z] - t[r]) * (t[r + 1] + t[z + 1]), z = r;
  return v;
}
class fRt {
  static triangulate(e, f, n = 2) {
    return RFt(e, f, n);
  }
}
class m9 {
  static area(e) {
    const f = e.length;
    let n = 0;
    for (let v = f - 1, r = 0; r < f; v = r++) n += e[v].x * e[r].y - e[r].x * e[v].y;
    return n * 0.5;
  }
  static isClockWise(e) {
    return m9.area(e) < 0;
  }
  static triangulateShape(e, f) {
    const n = [], v = [], r = [];
    QK(e), _K(n, e);
    let z = e.length;
    f.forEach(QK);
    for (let b = 0; b < f.length; b++) v.push(z), z += f[b].length, _K(n, f[b]);
    const u = fRt.triangulate(n, v);
    for (let b = 0; b < u.length; b += 3) r.push(u.slice(b, b + 3));
    return r;
  }
}
function QK(t) {
  const e = t.length;
  e > 2 && t[e - 1].equals(t[0]) && t.pop();
}
function _K(t, e) {
  for (let f = 0; f < e.length; f++) t.push(e[f].x), t.push(e[f].y);
}
class kM extends bv {
  constructor(e = new Is([new Me(0.5, 0.5), new Me(-0.5, 0.5), new Me(-0.5, -0.5), new Me(0.5, -0.5)]), f = {}) {
    super(), this.type = "ExtrudeGeometry", this.parameters = { shapes: e, options: f }, e = Array.isArray(e) ? e : [e];
    const n = this, v = [], r = [];
    for (let u = 0, b = e.length; u < b; u++) {
      const a = e[u];
      z(a);
    }
    this.setAttribute("position", new ln(v, 3)), this.setAttribute("uv", new ln(r, 2)), this.computeVertexNormals();
    function z(u) {
      const b = [], a = f.curveSegments !== void 0 ? f.curveSegments : 12, P = f.steps !== void 0 ? f.steps : 1, s = f.depth !== void 0 ? f.depth : 1;
      let d = f.bevelEnabled !== void 0 ? f.bevelEnabled : true, c = f.bevelThickness !== void 0 ? f.bevelThickness : 0.2, l = f.bevelSize !== void 0 ? f.bevelSize : c - 0.1, W = f.bevelOffset !== void 0 ? f.bevelOffset : 0, X = f.bevelSegments !== void 0 ? f.bevelSegments : 3;
      const y = f.extrudePath, m = f.UVGenerator !== void 0 ? f.UVGenerator : nRt;
      let j, x = false, L, O, N, i;
      y && (j = y.getSpacedPoints(P), x = true, d = false, L = y.computeFrenetFrames(P, false), O = new ht(), N = new ht(), i = new ht()), d || (X = 0, c = 0, l = 0, W = 0);
      const H = u.extractPoints(a);
      let M = H.shape;
      const p = H.holes;
      if (!m9.isClockWise(M)) {
        M = M.reverse();
        for (let tt = 0, zt = p.length; tt < zt; tt++) {
          const ut = p[tt];
          m9.isClockWise(ut) && (p[tt] = ut.reverse());
        }
      }
      function w(tt) {
        const ut = 10000000000000001e-36;
        let Pt = tt[0];
        for (let xt = 1; xt <= tt.length; xt++) {
          const Lt = xt % tt.length, Mt = tt[Lt], Ht = Mt.x - Pt.x, Gt = Mt.y - Pt.y, ft = Ht * Ht + Gt * Gt, $ = Math.max(Math.abs(Mt.x), Math.abs(Mt.y), Math.abs(Pt.x), Math.abs(Pt.y)), yt = ut * $ * $;
          if (ft <= yt) {
            tt.splice(Lt, 1), xt--;
            continue;
          }
          Pt = Mt;
        }
      }
      w(M), p.forEach(w);
      const Y = p.length, q = M;
      for (let tt = 0; tt < Y; tt++) {
        const zt = p[tt];
        M = M.concat(zt);
      }
      function D(tt, zt, ut) {
        return zt || console.error("THREE.ExtrudeGeometry: vec does not exist"), tt.clone().addScaledVector(zt, ut);
      }
      const V = M.length;
      function G(tt, zt, ut) {
        let Pt, xt, Lt;
        const Mt = tt.x - zt.x, Ht = tt.y - zt.y, Gt = ut.x - tt.x, ft = ut.y - tt.y, $ = Mt * Mt + Ht * Ht, yt = Mt * ft - Ht * Gt;
        if (Math.abs(yt) > Number.EPSILON) {
          const Tt = Math.sqrt($), Dt = Math.sqrt(Gt * Gt + ft * ft), pt = zt.x - Ht / Tt, ce = zt.y + Mt / Tt, ee = ut.x - ft / Dt, xe = ut.y + Gt / Dt, me = ((ee - pt) * ft - (xe - ce) * Gt) / (Mt * ft - Ht * Gt);
          Pt = pt + Mt * me - tt.x, xt = ce + Ht * me - tt.y;
          const ne = Pt * Pt + xt * xt;
          if (ne <= 2) return new Me(Pt, xt);
          Lt = Math.sqrt(ne / 2);
        } else {
          let Tt = false;
          Mt > Number.EPSILON ? Gt > Number.EPSILON && (Tt = true) : Mt < -Number.EPSILON ? Gt < -Number.EPSILON && (Tt = true) : Math.sign(Ht) === Math.sign(ft) && (Tt = true), Tt ? (Pt = -Ht, xt = Mt, Lt = Math.sqrt($)) : (Pt = Mt, xt = Ht, Lt = Math.sqrt($ / 2));
        }
        return new Me(Pt / Lt, xt / Lt);
      }
      const R = [];
      for (let tt = 0, zt = q.length, ut = zt - 1, Pt = tt + 1; tt < zt; tt++, ut++, Pt++) ut === zt && (ut = 0), Pt === zt && (Pt = 0), R[tt] = G(q[tt], q[ut], q[Pt]);
      const k = [];
      let K, _ = R.concat();
      for (let tt = 0, zt = Y; tt < zt; tt++) {
        const ut = p[tt];
        K = [];
        for (let Pt = 0, xt = ut.length, Lt = xt - 1, Mt = Pt + 1; Pt < xt; Pt++, Lt++, Mt++) Lt === xt && (Lt = 0), Mt === xt && (Mt = 0), K[Pt] = G(ut[Pt], ut[Lt], ut[Mt]);
        k.push(K), _ = _.concat(K);
      }
      let vt;
      if (X === 0) vt = m9.triangulateShape(q, p);
      else {
        const tt = [], zt = [];
        for (let ut = 0; ut < X; ut++) {
          const Pt = ut / X, xt = c * Math.cos(Pt * Math.PI / 2), Lt = l * Math.sin(Pt * Math.PI / 2) + W;
          for (let Mt = 0, Ht = q.length; Mt < Ht; Mt++) {
            const Gt = D(q[Mt], R[Mt], Lt);
            nt(Gt.x, Gt.y, -xt), Pt === 0 && tt.push(Gt);
          }
          for (let Mt = 0, Ht = Y; Mt < Ht; Mt++) {
            const Gt = p[Mt];
            K = k[Mt];
            const ft = [];
            for (let $ = 0, yt = Gt.length; $ < yt; $++) {
              const Tt = D(Gt[$], K[$], Lt);
              nt(Tt.x, Tt.y, -xt), Pt === 0 && ft.push(Tt);
            }
            Pt === 0 && zt.push(ft);
          }
        }
        vt = m9.triangulateShape(tt, zt);
      }
      const rt = vt.length, A = l + W;
      for (let tt = 0; tt < V; tt++) {
        const zt = d ? D(M[tt], _[tt], A) : M[tt];
        x ? (N.copy(L.normals[0]).multiplyScalar(zt.x), O.copy(L.binormals[0]).multiplyScalar(zt.y), i.copy(j[0]).add(N).add(O), nt(i.x, i.y, i.z)) : nt(zt.x, zt.y, 0);
      }
      for (let tt = 1; tt <= P; tt++) for (let zt = 0; zt < V; zt++) {
        const ut = d ? D(M[zt], _[zt], A) : M[zt];
        x ? (N.copy(L.normals[tt]).multiplyScalar(ut.x), O.copy(L.binormals[tt]).multiplyScalar(ut.y), i.copy(j[tt]).add(N).add(O), nt(i.x, i.y, i.z)) : nt(ut.x, ut.y, s / P * tt);
      }
      for (let tt = X - 1; tt >= 0; tt--) {
        const zt = tt / X, ut = c * Math.cos(zt * Math.PI / 2), Pt = l * Math.sin(zt * Math.PI / 2) + W;
        for (let xt = 0, Lt = q.length; xt < Lt; xt++) {
          const Mt = D(q[xt], R[xt], Pt);
          nt(Mt.x, Mt.y, s + ut);
        }
        for (let xt = 0, Lt = p.length; xt < Lt; xt++) {
          const Mt = p[xt];
          K = k[xt];
          for (let Ht = 0, Gt = Mt.length; Ht < Gt; Ht++) {
            const ft = D(Mt[Ht], K[Ht], Pt);
            x ? nt(ft.x, ft.y + j[P - 1].y, j[P - 1].x + ut) : nt(ft.x, ft.y, s + ut);
          }
        }
      }
      Q(), B();
      function Q() {
        const tt = v.length / 3;
        if (d) {
          let zt = 0, ut = V * zt;
          for (let Pt = 0; Pt < rt; Pt++) {
            const xt = vt[Pt];
            I(xt[2] + ut, xt[1] + ut, xt[0] + ut);
          }
          zt = P + X * 2, ut = V * zt;
          for (let Pt = 0; Pt < rt; Pt++) {
            const xt = vt[Pt];
            I(xt[0] + ut, xt[1] + ut, xt[2] + ut);
          }
        } else {
          for (let zt = 0; zt < rt; zt++) {
            const ut = vt[zt];
            I(ut[2], ut[1], ut[0]);
          }
          for (let zt = 0; zt < rt; zt++) {
            const ut = vt[zt];
            I(ut[0] + V * P, ut[1] + V * P, ut[2] + V * P);
          }
        }
        n.addGroup(tt, v.length / 3 - tt, 0);
      }
      function B() {
        const tt = v.length / 3;
        let zt = 0;
        et(q, zt), zt += q.length;
        for (let ut = 0, Pt = p.length; ut < Pt; ut++) {
          const xt = p[ut];
          et(xt, zt), zt += xt.length;
        }
        n.addGroup(tt, v.length / 3 - tt, 1);
      }
      function et(tt, zt) {
        let ut = tt.length;
        for (; --ut >= 0; ) {
          const Pt = ut;
          let xt = ut - 1;
          xt < 0 && (xt = tt.length - 1);
          for (let Lt = 0, Mt = P + X * 2; Lt < Mt; Lt++) {
            const Ht = V * Lt, Gt = V * (Lt + 1), ft = zt + Pt + Ht, $ = zt + xt + Ht, yt = zt + xt + Gt, Tt = zt + Pt + Gt;
            U(ft, $, yt, Tt);
          }
        }
      }
      function nt(tt, zt, ut) {
        b.push(tt), b.push(zt), b.push(ut);
      }
      function I(tt, zt, ut) {
        J(tt), J(zt), J(ut);
        const Pt = v.length / 3, xt = m.generateTopUV(n, v, Pt - 3, Pt - 2, Pt - 1);
        g(xt[0]), g(xt[1]), g(xt[2]);
      }
      function U(tt, zt, ut, Pt) {
        J(tt), J(zt), J(Pt), J(zt), J(ut), J(Pt);
        const xt = v.length / 3, Lt = m.generateSideWallUV(n, v, xt - 6, xt - 3, xt - 2, xt - 1);
        g(Lt[0]), g(Lt[1]), g(Lt[3]), g(Lt[1]), g(Lt[2]), g(Lt[3]);
      }
      function J(tt) {
        v.push(b[tt * 3 + 0]), v.push(b[tt * 3 + 1]), v.push(b[tt * 3 + 2]);
      }
      function g(tt) {
        r.push(tt.x), r.push(tt.y);
      }
    }
  }
  copy(e) {
    return super.copy(e), this.parameters = Object.assign({}, e.parameters), this;
  }
  toJSON() {
    const e = super.toJSON(), f = this.parameters.shapes, n = this.parameters.options;
    return vRt(f, n, e);
  }
  static fromJSON(e, f) {
    const n = [];
    for (let r = 0, z = e.shapes.length; r < z; r++) {
      const u = f[e.shapes[r]];
      n.push(u);
    }
    const v = e.options.extrudePath;
    return v !== void 0 && (e.options.extrudePath = new qM[v.type]().fromJSON(v)), new kM(n, e.options);
  }
}
const nRt = { generateTopUV: function(t, e, f, n, v) {
  const r = e[f * 3], z = e[f * 3 + 1], u = e[n * 3], b = e[n * 3 + 1], a = e[v * 3], P = e[v * 3 + 1];
  return [new Me(r, z), new Me(u, b), new Me(a, P)];
}, generateSideWallUV: function(t, e, f, n, v, r) {
  const z = e[f * 3], u = e[f * 3 + 1], b = e[f * 3 + 2], a = e[n * 3], P = e[n * 3 + 1], s = e[n * 3 + 2], d = e[v * 3], c = e[v * 3 + 1], l = e[v * 3 + 2], W = e[r * 3], X = e[r * 3 + 1], y = e[r * 3 + 2];
  return Math.abs(u - P) < Math.abs(z - a) ? [new Me(z, 1 - b), new Me(a, 1 - s), new Me(d, 1 - l), new Me(W, 1 - y)] : [new Me(u, 1 - b), new Me(P, 1 - s), new Me(c, 1 - l), new Me(X, 1 - y)];
} };
function vRt(t, e, f) {
  if (f.shapes = [], Array.isArray(t)) for (let n = 0, v = t.length; n < v; n++) {
    const r = t[n];
    f.shapes.push(r.uuid);
  }
  else f.shapes.push(t.uuid);
  return f.options = Object.assign({}, e), e.extrudePath !== void 0 && (f.options.extrudePath = e.extrudePath.toJSON()), f;
}
class j9 extends bv {
  constructor(e = 1, f = 1, n = 1, v = 1) {
    super(), this.type = "PlaneGeometry", this.parameters = { width: e, height: f, widthSegments: n, heightSegments: v };
    const r = e / 2, z = f / 2, u = Math.floor(n), b = Math.floor(v), a = u + 1, P = b + 1, s = e / u, d = f / b, c = [], l = [], W = [], X = [];
    for (let y = 0; y < P; y++) {
      const m = y * d - z;
      for (let j = 0; j < a; j++) {
        const x = j * s - r;
        l.push(x, -m, 0), W.push(0, 0, 1), X.push(j / u), X.push(1 - y / b);
      }
    }
    for (let y = 0; y < b; y++) for (let m = 0; m < u; m++) {
      const j = m + a * y, x = m + a * (y + 1), L = m + 1 + a * (y + 1), O = m + 1 + a * y;
      c.push(j, x, O), c.push(x, L, O);
    }
    this.setIndex(c), this.setAttribute("position", new ln(l, 3)), this.setAttribute("normal", new ln(W, 3)), this.setAttribute("uv", new ln(X, 2));
  }
  copy(e) {
    return super.copy(e), this.parameters = Object.assign({}, e.parameters), this;
  }
  static fromJSON(e) {
    return new j9(e.width, e.height, e.widthSegments, e.heightSegments);
  }
}
class U6 extends bv {
  constructor(e = new Is([new Me(0, 0.5), new Me(-0.5, -0.5), new Me(0.5, -0.5)]), f = 12) {
    super(), this.type = "ShapeGeometry", this.parameters = { shapes: e, curveSegments: f };
    const n = [], v = [], r = [], z = [];
    let u = 0, b = 0;
    if (Array.isArray(e) === false) a(e);
    else for (let P = 0; P < e.length; P++) a(e[P]), this.addGroup(u, b, P), u += b, b = 0;
    this.setIndex(n), this.setAttribute("position", new ln(v, 3)), this.setAttribute("normal", new ln(r, 3)), this.setAttribute("uv", new ln(z, 2));
    function a(P) {
      const s = v.length / 3, d = P.extractPoints(f);
      let c = d.shape;
      const l = d.holes;
      m9.isClockWise(c) === false && (c = c.reverse());
      for (let X = 0, y = l.length; X < y; X++) {
        const m = l[X];
        m9.isClockWise(m) === true && (l[X] = m.reverse());
      }
      const W = m9.triangulateShape(c, l);
      for (let X = 0, y = l.length; X < y; X++) {
        const m = l[X];
        c = c.concat(m);
      }
      for (let X = 0, y = c.length; X < y; X++) {
        const m = c[X];
        v.push(m.x, m.y, 0), r.push(0, 0, 1), z.push(m.x, m.y);
      }
      for (let X = 0, y = W.length; X < y; X++) {
        const m = W[X], j = m[0] + s, x = m[1] + s, L = m[2] + s;
        n.push(j, x, L), b += 3;
      }
    }
  }
  copy(e) {
    return super.copy(e), this.parameters = Object.assign({}, e.parameters), this;
  }
  toJSON() {
    const e = super.toJSON(), f = this.parameters.shapes;
    return rRt(f, e);
  }
  static fromJSON(e, f) {
    const n = [];
    for (let v = 0, r = e.shapes.length; v < r; v++) {
      const z = f[e.shapes[v]];
      n.push(z);
    }
    return new U6(n, e.curveSegments);
  }
}
function rRt(t, e) {
  if (e.shapes = [], Array.isArray(t)) for (let f = 0, n = t.length; f < n; f++) {
    const v = t[f];
    e.shapes.push(v.uuid);
  }
  else e.shapes.push(t.uuid);
  return e;
}
class I6 extends bv {
  constructor(e = 1, f = 32, n = 16, v = 0, r = Math.PI * 2, z = 0, u = Math.PI) {
    super(), this.type = "SphereGeometry", this.parameters = { radius: e, widthSegments: f, heightSegments: n, phiStart: v, phiLength: r, thetaStart: z, thetaLength: u }, f = Math.max(3, Math.floor(f)), n = Math.max(2, Math.floor(n));
    const b = Math.min(z + u, Math.PI);
    let a = 0;
    const P = [], s = new ht(), d = new ht(), c = [], l = [], W = [], X = [];
    for (let y = 0; y <= n; y++) {
      const m = [], j = y / n;
      let x = 0;
      y === 0 && z === 0 ? x = 0.5 / f : y === n && b === Math.PI && (x = -0.5 / f);
      for (let L = 0; L <= f; L++) {
        const O = L / f;
        s.x = -e * Math.cos(v + O * r) * Math.sin(z + j * u), s.y = e * Math.cos(z + j * u), s.z = e * Math.sin(v + O * r) * Math.sin(z + j * u), l.push(s.x, s.y, s.z), d.copy(s).normalize(), W.push(d.x, d.y, d.z), X.push(O + x, 1 - j), m.push(a++);
      }
      P.push(m);
    }
    for (let y = 0; y < n; y++) for (let m = 0; m < f; m++) {
      const j = P[y][m + 1], x = P[y][m], L = P[y + 1][m], O = P[y + 1][m + 1];
      (y !== 0 || z > 0) && c.push(j, x, O), (y !== n - 1 || b < Math.PI) && c.push(x, L, O);
    }
    this.setIndex(c), this.setAttribute("position", new ln(l, 3)), this.setAttribute("normal", new ln(W, 3)), this.setAttribute("uv", new ln(X, 2));
  }
  copy(e) {
    return super.copy(e), this.parameters = Object.assign({}, e.parameters), this;
  }
  static fromJSON(e) {
    return new I6(e.radius, e.widthSegments, e.heightSegments, e.phiStart, e.phiLength, e.thetaStart, e.thetaLength);
  }
}
class x7 extends ob {
  constructor(e) {
    super(), this.isMeshStandardMaterial = true, this.type = "MeshStandardMaterial", this.defines = { STANDARD: "" }, this.color = new Nf(16777215), this.roughness = 1, this.metalness = 0, this.map = null, this.lightMap = null, this.lightMapIntensity = 1, this.aoMap = null, this.aoMapIntensity = 1, this.emissive = new Nf(0), this.emissiveIntensity = 1, this.emissiveMap = null, this.bumpMap = null, this.bumpScale = 1, this.normalMap = null, this.normalMapType = EI, this.normalScale = new Me(1, 1), this.displacementMap = null, this.displacementScale = 1, this.displacementBias = 0, this.roughnessMap = null, this.metalnessMap = null, this.alphaMap = null, this.envMap = null, this.envMapRotation = new xz(), this.envMapIntensity = 1, this.wireframe = false, this.wireframeLinewidth = 1, this.wireframeLinecap = "round", this.wireframeLinejoin = "round", this.flatShading = false, this.fog = true, this.setValues(e);
  }
  copy(e) {
    return super.copy(e), this.defines = { STANDARD: "" }, this.color.copy(e.color), this.roughness = e.roughness, this.metalness = e.metalness, this.map = e.map, this.lightMap = e.lightMap, this.lightMapIntensity = e.lightMapIntensity, this.aoMap = e.aoMap, this.aoMapIntensity = e.aoMapIntensity, this.emissive.copy(e.emissive), this.emissiveMap = e.emissiveMap, this.emissiveIntensity = e.emissiveIntensity, this.bumpMap = e.bumpMap, this.bumpScale = e.bumpScale, this.normalMap = e.normalMap, this.normalMapType = e.normalMapType, this.normalScale.copy(e.normalScale), this.displacementMap = e.displacementMap, this.displacementScale = e.displacementScale, this.displacementBias = e.displacementBias, this.roughnessMap = e.roughnessMap, this.metalnessMap = e.metalnessMap, this.alphaMap = e.alphaMap, this.envMap = e.envMap, this.envMapRotation.copy(e.envMapRotation), this.envMapIntensity = e.envMapIntensity, this.wireframe = e.wireframe, this.wireframeLinewidth = e.wireframeLinewidth, this.wireframeLinecap = e.wireframeLinecap, this.wireframeLinejoin = e.wireframeLinejoin, this.flatShading = e.flatShading, this.fog = e.fog, this;
  }
}
class zRt extends x7 {
  constructor(e) {
    super(), this.isMeshPhysicalMaterial = true, this.defines = { STANDARD: "", PHYSICAL: "" }, this.type = "MeshPhysicalMaterial", this.anisotropyRotation = 0, this.anisotropyMap = null, this.clearcoatMap = null, this.clearcoatRoughness = 0, this.clearcoatRoughnessMap = null, this.clearcoatNormalScale = new Me(1, 1), this.clearcoatNormalMap = null, this.ior = 1.5, Object.defineProperty(this, "reflectivity", { get: function() {
      return pf(2.5 * (this.ior - 1) / (this.ior + 1), 0, 1);
    }, set: function(f) {
      this.ior = (1 + 0.4 * f) / (1 - 0.4 * f);
    } }), this.iridescenceMap = null, this.iridescenceIOR = 1.3, this.iridescenceThicknessRange = [100, 400], this.iridescenceThicknessMap = null, this.sheenColor = new Nf(0), this.sheenColorMap = null, this.sheenRoughness = 1, this.sheenRoughnessMap = null, this.transmissionMap = null, this.thickness = 0, this.thicknessMap = null, this.attenuationDistance = 1 / 0, this.attenuationColor = new Nf(1, 1, 1), this.specularIntensity = 1, this.specularIntensityMap = null, this.specularColor = new Nf(1, 1, 1), this.specularColorMap = null, this._anisotropy = 0, this._clearcoat = 0, this._dispersion = 0, this._iridescence = 0, this._sheen = 0, this._transmission = 0, this.setValues(e);
  }
  get anisotropy() {
    return this._anisotropy;
  }
  set anisotropy(e) {
    this._anisotropy > 0 != e > 0 && this.version++, this._anisotropy = e;
  }
  get clearcoat() {
    return this._clearcoat;
  }
  set clearcoat(e) {
    this._clearcoat > 0 != e > 0 && this.version++, this._clearcoat = e;
  }
  get iridescence() {
    return this._iridescence;
  }
  set iridescence(e) {
    this._iridescence > 0 != e > 0 && this.version++, this._iridescence = e;
  }
  get dispersion() {
    return this._dispersion;
  }
  set dispersion(e) {
    this._dispersion > 0 != e > 0 && this.version++, this._dispersion = e;
  }
  get sheen() {
    return this._sheen;
  }
  set sheen(e) {
    this._sheen > 0 != e > 0 && this.version++, this._sheen = e;
  }
  get transmission() {
    return this._transmission;
  }
  set transmission(e) {
    this._transmission > 0 != e > 0 && this.version++, this._transmission = e;
  }
  copy(e) {
    return super.copy(e), this.defines = { STANDARD: "", PHYSICAL: "" }, this.anisotropy = e.anisotropy, this.anisotropyRotation = e.anisotropyRotation, this.anisotropyMap = e.anisotropyMap, this.clearcoat = e.clearcoat, this.clearcoatMap = e.clearcoatMap, this.clearcoatRoughness = e.clearcoatRoughness, this.clearcoatRoughnessMap = e.clearcoatRoughnessMap, this.clearcoatNormalMap = e.clearcoatNormalMap, this.clearcoatNormalScale.copy(e.clearcoatNormalScale), this.dispersion = e.dispersion, this.ior = e.ior, this.iridescence = e.iridescence, this.iridescenceMap = e.iridescenceMap, this.iridescenceIOR = e.iridescenceIOR, this.iridescenceThicknessRange = [...e.iridescenceThicknessRange], this.iridescenceThicknessMap = e.iridescenceThicknessMap, this.sheen = e.sheen, this.sheenColor.copy(e.sheenColor), this.sheenColorMap = e.sheenColorMap, this.sheenRoughness = e.sheenRoughness, this.sheenRoughnessMap = e.sheenRoughnessMap, this.transmission = e.transmission, this.transmissionMap = e.transmissionMap, this.thickness = e.thickness, this.thicknessMap = e.thicknessMap, this.attenuationDistance = e.attenuationDistance, this.attenuationColor.copy(e.attenuationColor), this.specularIntensity = e.specularIntensity, this.specularIntensityMap = e.specularIntensityMap, this.specularColor.copy(e.specularColor), this.specularColorMap = e.specularColorMap, this;
  }
}
class uRt extends ob {
  constructor(e) {
    super(), this.isMeshDepthMaterial = true, this.type = "MeshDepthMaterial", this.depthPacking = xqt, this.map = null, this.alphaMap = null, this.displacementMap = null, this.displacementScale = 1, this.displacementBias = 0, this.wireframe = false, this.wireframeLinewidth = 1, this.setValues(e);
  }
  copy(e) {
    return super.copy(e), this.depthPacking = e.depthPacking, this.map = e.map, this.alphaMap = e.alphaMap, this.displacementMap = e.displacementMap, this.displacementScale = e.displacementScale, this.displacementBias = e.displacementBias, this.wireframe = e.wireframe, this.wireframeLinewidth = e.wireframeLinewidth, this;
  }
}
class bRt extends ob {
  constructor(e) {
    super(), this.isMeshDistanceMaterial = true, this.type = "MeshDistanceMaterial", this.map = null, this.alphaMap = null, this.displacementMap = null, this.displacementScale = 1, this.displacementBias = 0, this.setValues(e);
  }
  copy(e) {
    return super.copy(e), this.map = e.map, this.alphaMap = e.alphaMap, this.displacementMap = e.displacementMap, this.displacementScale = e.displacementScale, this.displacementBias = e.displacementBias, this;
  }
}
class $K extends zv {
  constructor(e, f = 1) {
    super(), this.isLight = true, this.type = "Light", this.color = new Nf(e), this.intensity = f;
  }
  dispose() {
  }
  copy(e, f) {
    return super.copy(e, f), this.color.copy(e.color), this.intensity = e.intensity, this;
  }
  toJSON(e) {
    const f = super.toJSON(e);
    return f.object.color = this.color.getHex(), f.object.intensity = this.intensity, this.groundColor !== void 0 && (f.object.groundColor = this.groundColor.getHex()), this.distance !== void 0 && (f.object.distance = this.distance), this.angle !== void 0 && (f.object.angle = this.angle), this.decay !== void 0 && (f.object.decay = this.decay), this.penumbra !== void 0 && (f.object.penumbra = this.penumbra), this.shadow !== void 0 && (f.object.shadow = this.shadow.toJSON()), this.target !== void 0 && (f.object.target = this.target.uuid), f;
  }
}
class aRt extends $K {
  constructor(e, f, n) {
    super(e, n), this.isHemisphereLight = true, this.type = "HemisphereLight", this.position.copy(zv.DEFAULT_UP), this.updateMatrix(), this.groundColor = new Nf(f);
  }
  copy(e, f) {
    return super.copy(e, f), this.groundColor.copy(e.groundColor), this;
  }
}
const JM = new cn();
const tE = new ht();
const eE = new ht();
class PRt {
  constructor(e) {
    this.camera = e, this.intensity = 1, this.bias = 0, this.normalBias = 0, this.radius = 1, this.blurSamples = 8, this.mapSize = new Me(512, 512), this.mapType = yz, this.map = null, this.mapPass = null, this.matrix = new cn(), this.autoUpdate = true, this.needsUpdate = false, this._frustum = new MM(), this._frameExtents = new Me(1, 1), this._viewportCount = 1, this._viewports = [new Un(0, 0, 1, 1)];
  }
  getViewportCount() {
    return this._viewportCount;
  }
  getFrustum() {
    return this._frustum;
  }
  updateMatrices(e) {
    const f = this.camera, n = this.matrix;
    tE.setFromMatrixPosition(e.matrixWorld), f.position.copy(tE), eE.setFromMatrixPosition(e.target.matrixWorld), f.lookAt(eE), f.updateMatrixWorld(), JM.multiplyMatrices(f.projectionMatrix, f.matrixWorldInverse), this._frustum.setFromProjectionMatrix(JM, f.coordinateSystem, f.reversedDepth), f.reversedDepth ? n.set(0.5, 0, 0, 0.5, 0, 0.5, 0, 0.5, 0, 0, 1, 0, 0, 0, 0, 1) : n.set(0.5, 0, 0, 0.5, 0, 0.5, 0, 0.5, 0, 0, 0.5, 0.5, 0, 0, 0, 1), n.multiply(JM);
  }
  getViewport(e) {
    return this._viewports[e];
  }
  getFrameExtents() {
    return this._frameExtents;
  }
  dispose() {
    this.map && this.map.dispose(), this.mapPass && this.mapPass.dispose();
  }
  copy(e) {
    return this.camera = e.camera.clone(), this.intensity = e.intensity, this.bias = e.bias, this.radius = e.radius, this.autoUpdate = e.autoUpdate, this.needsUpdate = e.needsUpdate, this.normalBias = e.normalBias, this.blurSamples = e.blurSamples, this.mapSize.copy(e.mapSize), this;
  }
  clone() {
    return new this.constructor().copy(this);
  }
  toJSON() {
    const e = {};
    return this.intensity !== 1 && (e.intensity = this.intensity), this.bias !== 0 && (e.bias = this.bias), this.normalBias !== 0 && (e.normalBias = this.normalBias), this.radius !== 1 && (e.radius = this.radius), (this.mapSize.x !== 512 || this.mapSize.y !== 512) && (e.mapSize = this.mapSize.toArray()), e.camera = this.camera.toJSON(false).object, delete e.camera.matrix, e;
  }
}
class fE extends jK {
  constructor(e = -1, f = 1, n = 1, v = -1, r = 0.1, z = 2e3) {
    super(), this.isOrthographicCamera = true, this.type = "OrthographicCamera", this.zoom = 1, this.view = null, this.left = e, this.right = f, this.top = n, this.bottom = v, this.near = r, this.far = z, this.updateProjectionMatrix();
  }
  copy(e, f) {
    return super.copy(e, f), this.left = e.left, this.right = e.right, this.top = e.top, this.bottom = e.bottom, this.near = e.near, this.far = e.far, this.zoom = e.zoom, this.view = e.view === null ? null : Object.assign({}, e.view), this;
  }
  setViewOffset(e, f, n, v, r, z) {
    this.view === null && (this.view = { enabled: true, fullWidth: 1, fullHeight: 1, offsetX: 0, offsetY: 0, width: 1, height: 1 }), this.view.enabled = true, this.view.fullWidth = e, this.view.fullHeight = f, this.view.offsetX = n, this.view.offsetY = v, this.view.width = r, this.view.height = z, this.updateProjectionMatrix();
  }
  clearViewOffset() {
    this.view !== null && (this.view.enabled = false), this.updateProjectionMatrix();
  }
  updateProjectionMatrix() {
    const e = (this.right - this.left) / (2 * this.zoom), f = (this.top - this.bottom) / (2 * this.zoom), n = (this.right + this.left) / 2, v = (this.top + this.bottom) / 2;
    let r = n - e, z = n + e, u = v + f, b = v - f;
    if (this.view !== null && this.view.enabled) {
      const a = (this.right - this.left) / this.view.fullWidth / this.zoom, P = (this.top - this.bottom) / this.view.fullHeight / this.zoom;
      r += a * this.view.offsetX, z = r + a * this.view.width, u -= P * this.view.offsetY, b = u - P * this.view.height;
    }
    this.projectionMatrix.makeOrthographic(r, z, u, b, this.near, this.far, this.coordinateSystem, this.reversedDepth), this.projectionMatrixInverse.copy(this.projectionMatrix).invert();
  }
  toJSON(e) {
    const f = super.toJSON(e);
    return f.object.zoom = this.zoom, f.object.left = this.left, f.object.right = this.right, f.object.top = this.top, f.object.bottom = this.bottom, f.object.near = this.near, f.object.far = this.far, this.view !== null && (f.object.view = Object.assign({}, this.view)), f;
  }
}
class sRt extends PRt {
  constructor() {
    super(new fE(-5, 5, 5, -5, 0.5, 500)), this.isDirectionalLightShadow = true;
  }
}
class dRt extends $K {
  constructor(e, f) {
    super(e, f), this.isDirectionalLight = true, this.type = "DirectionalLight", this.position.copy(zv.DEFAULT_UP), this.updateMatrix(), this.target = new zv(), this.shadow = new sRt();
  }
  dispose() {
    this.shadow.dispose();
  }
  copy(e) {
    return super.copy(e), this.target = e.target.clone(), this.shadow = e.shadow.clone(), this;
  }
}
class cRt extends qr {
  constructor(e = []) {
    super(), this.isArrayCamera = true, this.isMultiViewCamera = false, this.cameras = e;
  }
}
const nE = new cn();
class lRt {
  constructor(e, f, n = 0, v = 1 / 0) {
    this.ray = new W6(e, f), this.near = n, this.far = v, this.camera = null, this.layers = new PM(), this.params = { Mesh: {}, Line: { threshold: 1 }, LOD: {}, Points: { threshold: 1 }, Sprite: {} };
  }
  set(e, f) {
    this.ray.set(e, f);
  }
  setFromCamera(e, f) {
    f.isPerspectiveCamera ? (this.ray.origin.setFromMatrixPosition(f.matrixWorld), this.ray.direction.set(e.x, e.y, 0.5).unproject(f).sub(this.ray.origin).normalize(), this.camera = f) : f.isOrthographicCamera ? (this.ray.origin.set(e.x, e.y, (f.near + f.far) / (f.near - f.far)).unproject(f), this.ray.direction.set(0, 0, -1).transformDirection(f.matrixWorld), this.camera = f) : console.error("THREE.Raycaster: Unsupported camera type: " + f.type);
  }
  setFromXRController(e) {
    return nE.identity().extractRotation(e.matrixWorld), this.ray.origin.setFromMatrixPosition(e.matrixWorld), this.ray.direction.set(0, 0, -1).applyMatrix4(nE), this;
  }
  intersectObject(e, f = true, n = []) {
    return gM(e, this, n, f), n.sort(vE), n;
  }
  intersectObjects(e, f = true, n = []) {
    for (let v = 0, r = e.length; v < r; v++) gM(e[v], this, n, f);
    return n.sort(vE), n;
  }
}
function vE(t, e) {
  return t.distance - e.distance;
}
function gM(t, e, f, n) {
  let v = true;
  if (t.layers.test(e.layers) && t.raycast(e, f) === false && (v = false), v === true && n === true) {
    const r = t.children;
    for (let z = 0, u = r.length; z < u; z++) gM(r[z], e, f, true);
  }
}
class rE {
  constructor(e = 1, f = 0, n = 0) {
    this.radius = e, this.phi = f, this.theta = n;
  }
  set(e, f, n) {
    return this.radius = e, this.phi = f, this.theta = n, this;
  }
  copy(e) {
    return this.radius = e.radius, this.phi = e.phi, this.theta = e.theta, this;
  }
  makeSafe() {
    return this.phi = pf(this.phi, 1e-6, Math.PI - 1e-6), this;
  }
  setFromVector3(e) {
    return this.setFromCartesianCoords(e.x, e.y, e.z);
  }
  setFromCartesianCoords(e, f, n) {
    return this.radius = Math.sqrt(e * e + f * f + n * n), this.radius === 0 ? (this.theta = 0, this.phi = 0) : (this.theta = Math.atan2(e, n), this.phi = Math.acos(pf(f / this.radius, -1, 1))), this;
  }
  clone() {
    return new this.constructor().copy(this);
  }
}
class XRt extends qK {
  constructor(e, f = 16776960) {
    const n = new Uint16Array([0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7]), v = [1, 1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1, 1, 1, -1, -1, 1, -1, -1, -1, -1, 1, -1, -1], r = new bv();
    r.setIndex(new ov(n, 1)), r.setAttribute("position", new ln(v, 3)), super(r, new iM({ color: f, toneMapped: false })), this.box = e, this.type = "Box3Helper", this.geometry.computeBoundingSphere();
  }
  updateMatrixWorld(e) {
    const f = this.box;
    f.isEmpty() || (f.getCenter(this.position), f.getSize(this.scale), this.scale.multiplyScalar(0.5), super.updateMatrixWorld(e));
  }
  dispose() {
    this.geometry.dispose(), this.material.dispose();
  }
}
class WRt extends Nb {
  constructor(e, f = null) {
    super(), this.object = e, this.domElement = f, this.enabled = true, this.state = -1, this.keys = {}, this.mouseButtons = { LEFT: null, MIDDLE: null, RIGHT: null }, this.touches = { ONE: null, TWO: null };
  }
  connect(e) {
    if (e === void 0) {
      console.warn("THREE.Controls: connect() now requires an element.");
      return;
    }
    this.domElement !== null && this.disconnect(), this.domElement = e;
  }
  disconnect() {
  }
  dispose() {
  }
  update() {
  }
}
function zE(t, e, f, n) {
  const v = yRt(n);
  switch (f) {
    case UI:
      return t * e;
    case jN:
      return t * e / v.components * v.byteLength;
    case xN:
      return t * e / v.components * v.byteLength;
    case KI:
      return t * e * 2 / v.components * v.byteLength;
    case LN:
      return t * e * 2 / v.components * v.byteLength;
    case II:
      return t * e * 3 / v.components * v.byteLength;
    case W7:
      return t * e * 4 / v.components * v.byteLength;
    case ON:
      return t * e * 4 / v.components * v.byteLength;
    case n6:
    case v6:
      return Math.floor((t + 3) / 4) * Math.floor((e + 3) / 4) * 8;
    case r6:
    case z6:
      return Math.floor((t + 3) / 4) * Math.floor((e + 3) / 4) * 16;
    case NN:
    case iN:
      return Math.max(t, 16) * Math.max(e, 8) / 4;
    case HN:
    case MN:
      return Math.max(t, 8) * Math.max(e, 8) / 2;
    case pN:
    case TN:
      return Math.floor((t + 3) / 4) * Math.floor((e + 3) / 4) * 8;
    case oN:
      return Math.floor((t + 3) / 4) * Math.floor((e + 3) / 4) * 16;
    case wN:
      return Math.floor((t + 3) / 4) * Math.floor((e + 3) / 4) * 16;
    case hN:
      return Math.floor((t + 4) / 5) * Math.floor((e + 3) / 4) * 16;
    case GN:
      return Math.floor((t + 4) / 5) * Math.floor((e + 4) / 5) * 16;
    case VN:
      return Math.floor((t + 5) / 6) * Math.floor((e + 4) / 5) * 16;
    case YN:
      return Math.floor((t + 5) / 6) * Math.floor((e + 5) / 6) * 16;
    case DN:
      return Math.floor((t + 7) / 8) * Math.floor((e + 4) / 5) * 16;
    case qN:
      return Math.floor((t + 7) / 8) * Math.floor((e + 5) / 6) * 16;
    case FN:
      return Math.floor((t + 7) / 8) * Math.floor((e + 7) / 8) * 16;
    case RN:
      return Math.floor((t + 9) / 10) * Math.floor((e + 4) / 5) * 16;
    case kN:
      return Math.floor((t + 9) / 10) * Math.floor((e + 5) / 6) * 16;
    case JN:
      return Math.floor((t + 9) / 10) * Math.floor((e + 7) / 8) * 16;
    case gN:
      return Math.floor((t + 9) / 10) * Math.floor((e + 9) / 10) * 16;
    case SN:
      return Math.floor((t + 11) / 12) * Math.floor((e + 9) / 10) * 16;
    case BN:
      return Math.floor((t + 11) / 12) * Math.floor((e + 11) / 12) * 16;
    case UN:
    case IN:
    case KN:
      return Math.ceil(t / 4) * Math.ceil(e / 4) * 16;
    case EN:
    case CN:
      return Math.ceil(t / 4) * Math.ceil(e / 4) * 8;
    case AN:
    case QN:
      return Math.ceil(t / 4) * Math.ceil(e / 4) * 16;
  }
  throw new Error(`Unable to determine texture byte length for ${f} format.`);
}
function yRt(t) {
  switch (t) {
    case yz:
    case JI:
      return { byteLength: 1, components: 1 };
    case Hs:
    case gI:
    case Ba:
      return { byteLength: 2, components: 1 };
    case ZN:
    case mN:
      return { byteLength: 2, components: 4 };
    case Hb:
    case yN:
    case Zz:
      return { byteLength: 4, components: 1 };
    case SI:
    case BI:
      return { byteLength: 4, components: 3 };
  }
  throw new Error(`Unknown texture type ${t}.`);
}
typeof __THREE_DEVTOOLS__ < "u" && __THREE_DEVTOOLS__.dispatchEvent(new CustomEvent("register", { detail: { revision: vN } })), typeof window < "u" && (window.__THREE__ ? console.warn("WARNING: Multiple instances of Three.js being imported.") : window.__THREE__ = vN);
function uE() {
  let t = null, e = false, f = null, n = null;
  function v(r, z) {
    f(r, z), n = t.requestAnimationFrame(v);
  }
  return { start: function() {
    e !== true && f !== null && (n = t.requestAnimationFrame(v), e = true);
  }, stop: function() {
    t.cancelAnimationFrame(n), e = false;
  }, setAnimationLoop: function(r) {
    f = r;
  }, setContext: function(r) {
    t = r;
  } };
}
function ZRt(t) {
  const e = /* @__PURE__ */ new WeakMap();
  function f(u, b) {
    const a = u.array, P = u.usage, s = a.byteLength, d = t.createBuffer();
    t.bindBuffer(b, d), t.bufferData(b, a, P), u.onUploadCallback();
    let c;
    if (a instanceof Float32Array) c = t.FLOAT;
    else if (typeof Float16Array < "u" && a instanceof Float16Array) c = t.HALF_FLOAT;
    else if (a instanceof Uint16Array) u.isFloat16BufferAttribute ? c = t.HALF_FLOAT : c = t.UNSIGNED_SHORT;
    else if (a instanceof Int16Array) c = t.SHORT;
    else if (a instanceof Uint32Array) c = t.UNSIGNED_INT;
    else if (a instanceof Int32Array) c = t.INT;
    else if (a instanceof Int8Array) c = t.BYTE;
    else if (a instanceof Uint8Array) c = t.UNSIGNED_BYTE;
    else if (a instanceof Uint8ClampedArray) c = t.UNSIGNED_BYTE;
    else throw new Error("THREE.WebGLAttributes: Unsupported buffer data format: " + a);
    return { buffer: d, type: c, bytesPerElement: a.BYTES_PER_ELEMENT, version: u.version, size: s };
  }
  function n(u, b, a) {
    const P = b.array, s = b.updateRanges;
    if (t.bindBuffer(a, u), s.length === 0) t.bufferSubData(a, 0, P);
    else {
      s.sort((c, l) => c.start - l.start);
      let d = 0;
      for (let c = 1; c < s.length; c++) {
        const l = s[d], W = s[c];
        W.start <= l.start + l.count + 1 ? l.count = Math.max(l.count, W.start + W.count - l.start) : (++d, s[d] = W);
      }
      s.length = d + 1;
      for (let c = 0, l = s.length; c < l; c++) {
        const W = s[c];
        t.bufferSubData(a, W.start * P.BYTES_PER_ELEMENT, P, W.start, W.count);
      }
      b.clearUpdateRanges();
    }
    b.onUploadCallback();
  }
  function v(u) {
    return u.isInterleavedBufferAttribute && (u = u.data), e.get(u);
  }
  function r(u) {
    u.isInterleavedBufferAttribute && (u = u.data);
    const b = e.get(u);
    b && (t.deleteBuffer(b.buffer), e.delete(u));
  }
  function z(u, b) {
    if (u.isInterleavedBufferAttribute && (u = u.data), u.isGLBufferAttribute) {
      const P = e.get(u);
      (!P || P.version < u.version) && e.set(u, { buffer: u.buffer, type: u.type, bytesPerElement: u.elementSize, version: u.version });
      return;
    }
    const a = e.get(u);
    if (a === void 0) e.set(u, f(u, b));
    else if (a.version < u.version) {
      if (a.size !== u.array.byteLength) throw new Error("THREE.WebGLAttributes: The size of the buffer attribute's array buffer does not match the original size. Resizing buffer attributes is not supported.");
      n(a.buffer, u, b), a.version = u.version;
    }
  }
  return { get: v, remove: r, update: z };
}
var mRt = `#ifdef USE_ALPHAHASH
	if ( diffuseColor.a < getAlphaHashThreshold( vPosition ) ) discard;
#endif`;
var jRt = `#ifdef USE_ALPHAHASH
	const float ALPHA_HASH_SCALE = 0.05;
	float hash2D( vec2 value ) {
		return fract( 1.0e4 * sin( 17.0 * value.x + 0.1 * value.y ) * ( 0.1 + abs( sin( 13.0 * value.y + value.x ) ) ) );
	}
	float hash3D( vec3 value ) {
		return hash2D( vec2( hash2D( value.xy ), value.z ) );
	}
	float getAlphaHashThreshold( vec3 position ) {
		float maxDeriv = max(
			length( dFdx( position.xyz ) ),
			length( dFdy( position.xyz ) )
		);
		float pixScale = 1.0 / ( ALPHA_HASH_SCALE * maxDeriv );
		vec2 pixScales = vec2(
			exp2( floor( log2( pixScale ) ) ),
			exp2( ceil( log2( pixScale ) ) )
		);
		vec2 alpha = vec2(
			hash3D( floor( pixScales.x * position.xyz ) ),
			hash3D( floor( pixScales.y * position.xyz ) )
		);
		float lerpFactor = fract( log2( pixScale ) );
		float x = ( 1.0 - lerpFactor ) * alpha.x + lerpFactor * alpha.y;
		float a = min( lerpFactor, 1.0 - lerpFactor );
		vec3 cases = vec3(
			x * x / ( 2.0 * a * ( 1.0 - a ) ),
			( x - 0.5 * a ) / ( 1.0 - a ),
			1.0 - ( ( 1.0 - x ) * ( 1.0 - x ) / ( 2.0 * a * ( 1.0 - a ) ) )
		);
		float threshold = ( x < ( 1.0 - a ) )
			? ( ( x < a ) ? cases.x : cases.y )
			: cases.z;
		return clamp( threshold , 1.0e-6, 1.0 );
	}
#endif`;
var xRt = `#ifdef USE_ALPHAMAP
	diffuseColor.a *= texture2D( alphaMap, vAlphaMapUv ).g;
#endif`;
var LRt = `#ifdef USE_ALPHAMAP
	uniform sampler2D alphaMap;
#endif`;
var ORt = `#ifdef USE_ALPHATEST
	#ifdef ALPHA_TO_COVERAGE
	diffuseColor.a = smoothstep( alphaTest, alphaTest + fwidth( diffuseColor.a ), diffuseColor.a );
	if ( diffuseColor.a == 0.0 ) discard;
	#else
	if ( diffuseColor.a < alphaTest ) discard;
	#endif
#endif`;
var HRt = `#ifdef USE_ALPHATEST
	uniform float alphaTest;
#endif`;
var NRt = `#ifdef USE_AOMAP
	float ambientOcclusion = ( texture2D( aoMap, vAoMapUv ).r - 1.0 ) * aoMapIntensity + 1.0;
	reflectedLight.indirectDiffuse *= ambientOcclusion;
	#if defined( USE_CLEARCOAT )
		clearcoatSpecularIndirect *= ambientOcclusion;
	#endif
	#if defined( USE_SHEEN )
		sheenSpecularIndirect *= ambientOcclusion;
	#endif
	#if defined( USE_ENVMAP ) && defined( STANDARD )
		float dotNV = saturate( dot( geometryNormal, geometryViewDir ) );
		reflectedLight.indirectSpecular *= computeSpecularOcclusion( dotNV, ambientOcclusion, material.roughness );
	#endif
#endif`;
var MRt = `#ifdef USE_AOMAP
	uniform sampler2D aoMap;
	uniform float aoMapIntensity;
#endif`;
var iRt = `#ifdef USE_BATCHING
	#if ! defined( GL_ANGLE_multi_draw )
	#define gl_DrawID _gl_DrawID
	uniform int _gl_DrawID;
	#endif
	uniform highp sampler2D batchingTexture;
	uniform highp usampler2D batchingIdTexture;
	mat4 getBatchingMatrix( const in float i ) {
		int size = textureSize( batchingTexture, 0 ).x;
		int j = int( i ) * 4;
		int x = j % size;
		int y = j / size;
		vec4 v1 = texelFetch( batchingTexture, ivec2( x, y ), 0 );
		vec4 v2 = texelFetch( batchingTexture, ivec2( x + 1, y ), 0 );
		vec4 v3 = texelFetch( batchingTexture, ivec2( x + 2, y ), 0 );
		vec4 v4 = texelFetch( batchingTexture, ivec2( x + 3, y ), 0 );
		return mat4( v1, v2, v3, v4 );
	}
	float getIndirectIndex( const in int i ) {
		int size = textureSize( batchingIdTexture, 0 ).x;
		int x = i % size;
		int y = i / size;
		return float( texelFetch( batchingIdTexture, ivec2( x, y ), 0 ).r );
	}
#endif
#ifdef USE_BATCHING_COLOR
	uniform sampler2D batchingColorTexture;
	vec3 getBatchingColor( const in float i ) {
		int size = textureSize( batchingColorTexture, 0 ).x;
		int j = int( i );
		int x = j % size;
		int y = j / size;
		return texelFetch( batchingColorTexture, ivec2( x, y ), 0 ).rgb;
	}
#endif`;
var pRt = `#ifdef USE_BATCHING
	mat4 batchingMatrix = getBatchingMatrix( getIndirectIndex( gl_DrawID ) );
#endif`;
var TRt = `vec3 transformed = vec3( position );
#ifdef USE_ALPHAHASH
	vPosition = vec3( position );
#endif`;
var oRt = `vec3 objectNormal = vec3( normal );
#ifdef USE_TANGENT
	vec3 objectTangent = vec3( tangent.xyz );
#endif`;
var wRt = `float G_BlinnPhong_Implicit( ) {
	return 0.25;
}
float D_BlinnPhong( const in float shininess, const in float dotNH ) {
	return RECIPROCAL_PI * ( shininess * 0.5 + 1.0 ) * pow( dotNH, shininess );
}
vec3 BRDF_BlinnPhong( const in vec3 lightDir, const in vec3 viewDir, const in vec3 normal, const in vec3 specularColor, const in float shininess ) {
	vec3 halfDir = normalize( lightDir + viewDir );
	float dotNH = saturate( dot( normal, halfDir ) );
	float dotVH = saturate( dot( viewDir, halfDir ) );
	vec3 F = F_Schlick( specularColor, 1.0, dotVH );
	float G = G_BlinnPhong_Implicit( );
	float D = D_BlinnPhong( shininess, dotNH );
	return F * ( G * D );
} // validated`;
var hRt = `#ifdef USE_IRIDESCENCE
	const mat3 XYZ_TO_REC709 = mat3(
		 3.2404542, -0.9692660,  0.0556434,
		-1.5371385,  1.8760108, -0.2040259,
		-0.4985314,  0.0415560,  1.0572252
	);
	vec3 Fresnel0ToIor( vec3 fresnel0 ) {
		vec3 sqrtF0 = sqrt( fresnel0 );
		return ( vec3( 1.0 ) + sqrtF0 ) / ( vec3( 1.0 ) - sqrtF0 );
	}
	vec3 IorToFresnel0( vec3 transmittedIor, float incidentIor ) {
		return pow2( ( transmittedIor - vec3( incidentIor ) ) / ( transmittedIor + vec3( incidentIor ) ) );
	}
	float IorToFresnel0( float transmittedIor, float incidentIor ) {
		return pow2( ( transmittedIor - incidentIor ) / ( transmittedIor + incidentIor ));
	}
	vec3 evalSensitivity( float OPD, vec3 shift ) {
		float phase = 2.0 * PI * OPD * 1.0e-9;
		vec3 val = vec3( 5.4856e-13, 4.4201e-13, 5.2481e-13 );
		vec3 pos = vec3( 1.6810e+06, 1.7953e+06, 2.2084e+06 );
		vec3 var = vec3( 4.3278e+09, 9.3046e+09, 6.6121e+09 );
		vec3 xyz = val * sqrt( 2.0 * PI * var ) * cos( pos * phase + shift ) * exp( - pow2( phase ) * var );
		xyz.x += 9.7470e-14 * sqrt( 2.0 * PI * 4.5282e+09 ) * cos( 2.2399e+06 * phase + shift[ 0 ] ) * exp( - 4.5282e+09 * pow2( phase ) );
		xyz /= 1.0685e-7;
		vec3 rgb = XYZ_TO_REC709 * xyz;
		return rgb;
	}
	vec3 evalIridescence( float outsideIOR, float eta2, float cosTheta1, float thinFilmThickness, vec3 baseF0 ) {
		vec3 I;
		float iridescenceIOR = mix( outsideIOR, eta2, smoothstep( 0.0, 0.03, thinFilmThickness ) );
		float sinTheta2Sq = pow2( outsideIOR / iridescenceIOR ) * ( 1.0 - pow2( cosTheta1 ) );
		float cosTheta2Sq = 1.0 - sinTheta2Sq;
		if ( cosTheta2Sq < 0.0 ) {
			return vec3( 1.0 );
		}
		float cosTheta2 = sqrt( cosTheta2Sq );
		float R0 = IorToFresnel0( iridescenceIOR, outsideIOR );
		float R12 = F_Schlick( R0, 1.0, cosTheta1 );
		float T121 = 1.0 - R12;
		float phi12 = 0.0;
		if ( iridescenceIOR < outsideIOR ) phi12 = PI;
		float phi21 = PI - phi12;
		vec3 baseIOR = Fresnel0ToIor( clamp( baseF0, 0.0, 0.9999 ) );		vec3 R1 = IorToFresnel0( baseIOR, iridescenceIOR );
		vec3 R23 = F_Schlick( R1, 1.0, cosTheta2 );
		vec3 phi23 = vec3( 0.0 );
		if ( baseIOR[ 0 ] < iridescenceIOR ) phi23[ 0 ] = PI;
		if ( baseIOR[ 1 ] < iridescenceIOR ) phi23[ 1 ] = PI;
		if ( baseIOR[ 2 ] < iridescenceIOR ) phi23[ 2 ] = PI;
		float OPD = 2.0 * iridescenceIOR * thinFilmThickness * cosTheta2;
		vec3 phi = vec3( phi21 ) + phi23;
		vec3 R123 = clamp( R12 * R23, 1e-5, 0.9999 );
		vec3 r123 = sqrt( R123 );
		vec3 Rs = pow2( T121 ) * R23 / ( vec3( 1.0 ) - R123 );
		vec3 C0 = R12 + Rs;
		I = C0;
		vec3 Cm = Rs - T121;
		for ( int m = 1; m <= 2; ++ m ) {
			Cm *= r123;
			vec3 Sm = 2.0 * evalSensitivity( float( m ) * OPD, float( m ) * phi );
			I += Cm * Sm;
		}
		return max( I, vec3( 0.0 ) );
	}
#endif`;
var GRt = `#ifdef USE_BUMPMAP
	uniform sampler2D bumpMap;
	uniform float bumpScale;
	vec2 dHdxy_fwd() {
		vec2 dSTdx = dFdx( vBumpMapUv );
		vec2 dSTdy = dFdy( vBumpMapUv );
		float Hll = bumpScale * texture2D( bumpMap, vBumpMapUv ).x;
		float dBx = bumpScale * texture2D( bumpMap, vBumpMapUv + dSTdx ).x - Hll;
		float dBy = bumpScale * texture2D( bumpMap, vBumpMapUv + dSTdy ).x - Hll;
		return vec2( dBx, dBy );
	}
	vec3 perturbNormalArb( vec3 surf_pos, vec3 surf_norm, vec2 dHdxy, float faceDirection ) {
		vec3 vSigmaX = normalize( dFdx( surf_pos.xyz ) );
		vec3 vSigmaY = normalize( dFdy( surf_pos.xyz ) );
		vec3 vN = surf_norm;
		vec3 R1 = cross( vSigmaY, vN );
		vec3 R2 = cross( vN, vSigmaX );
		float fDet = dot( vSigmaX, R1 ) * faceDirection;
		vec3 vGrad = sign( fDet ) * ( dHdxy.x * R1 + dHdxy.y * R2 );
		return normalize( abs( fDet ) * surf_norm - vGrad );
	}
#endif`;
var VRt = `#if NUM_CLIPPING_PLANES > 0
	vec4 plane;
	#ifdef ALPHA_TO_COVERAGE
		float distanceToPlane, distanceGradient;
		float clipOpacity = 1.0;
		#pragma unroll_loop_start
		for ( int i = 0; i < UNION_CLIPPING_PLANES; i ++ ) {
			plane = clippingPlanes[ i ];
			distanceToPlane = - dot( vClipPosition, plane.xyz ) + plane.w;
			distanceGradient = fwidth( distanceToPlane ) / 2.0;
			clipOpacity *= smoothstep( - distanceGradient, distanceGradient, distanceToPlane );
			if ( clipOpacity == 0.0 ) discard;
		}
		#pragma unroll_loop_end
		#if UNION_CLIPPING_PLANES < NUM_CLIPPING_PLANES
			float unionClipOpacity = 1.0;
			#pragma unroll_loop_start
			for ( int i = UNION_CLIPPING_PLANES; i < NUM_CLIPPING_PLANES; i ++ ) {
				plane = clippingPlanes[ i ];
				distanceToPlane = - dot( vClipPosition, plane.xyz ) + plane.w;
				distanceGradient = fwidth( distanceToPlane ) / 2.0;
				unionClipOpacity *= 1.0 - smoothstep( - distanceGradient, distanceGradient, distanceToPlane );
			}
			#pragma unroll_loop_end
			clipOpacity *= 1.0 - unionClipOpacity;
		#endif
		diffuseColor.a *= clipOpacity;
		if ( diffuseColor.a == 0.0 ) discard;
	#else
		#pragma unroll_loop_start
		for ( int i = 0; i < UNION_CLIPPING_PLANES; i ++ ) {
			plane = clippingPlanes[ i ];
			if ( dot( vClipPosition, plane.xyz ) > plane.w ) discard;
		}
		#pragma unroll_loop_end
		#if UNION_CLIPPING_PLANES < NUM_CLIPPING_PLANES
			bool clipped = true;
			#pragma unroll_loop_start
			for ( int i = UNION_CLIPPING_PLANES; i < NUM_CLIPPING_PLANES; i ++ ) {
				plane = clippingPlanes[ i ];
				clipped = ( dot( vClipPosition, plane.xyz ) > plane.w ) && clipped;
			}
			#pragma unroll_loop_end
			if ( clipped ) discard;
		#endif
	#endif
#endif`;
var YRt = `#if NUM_CLIPPING_PLANES > 0
	varying vec3 vClipPosition;
	uniform vec4 clippingPlanes[ NUM_CLIPPING_PLANES ];
#endif`;
var DRt = `#if NUM_CLIPPING_PLANES > 0
	varying vec3 vClipPosition;
#endif`;
var qRt = `#if NUM_CLIPPING_PLANES > 0
	vClipPosition = - mvPosition.xyz;
#endif`;
var FRt = `#if defined( USE_COLOR_ALPHA )
	diffuseColor *= vColor;
#elif defined( USE_COLOR )
	diffuseColor.rgb *= vColor;
#endif`;
var RRt = `#if defined( USE_COLOR_ALPHA )
	varying vec4 vColor;
#elif defined( USE_COLOR )
	varying vec3 vColor;
#endif`;
var kRt = `#if defined( USE_COLOR_ALPHA )
	varying vec4 vColor;
#elif defined( USE_COLOR ) || defined( USE_INSTANCING_COLOR ) || defined( USE_BATCHING_COLOR )
	varying vec3 vColor;
#endif`;
var JRt = `#if defined( USE_COLOR_ALPHA )
	vColor = vec4( 1.0 );
#elif defined( USE_COLOR ) || defined( USE_INSTANCING_COLOR ) || defined( USE_BATCHING_COLOR )
	vColor = vec3( 1.0 );
#endif
#ifdef USE_COLOR
	vColor *= color;
#endif
#ifdef USE_INSTANCING_COLOR
	vColor.xyz *= instanceColor.xyz;
#endif
#ifdef USE_BATCHING_COLOR
	vec3 batchingColor = getBatchingColor( getIndirectIndex( gl_DrawID ) );
	vColor.xyz *= batchingColor.xyz;
#endif`;
var gRt = `#define PI 3.141592653589793
#define PI2 6.283185307179586
#define PI_HALF 1.5707963267948966
#define RECIPROCAL_PI 0.3183098861837907
#define RECIPROCAL_PI2 0.15915494309189535
#define EPSILON 1e-6
#ifndef saturate
#define saturate( a ) clamp( a, 0.0, 1.0 )
#endif
#define whiteComplement( a ) ( 1.0 - saturate( a ) )
float pow2( const in float x ) { return x*x; }
vec3 pow2( const in vec3 x ) { return x*x; }
float pow3( const in float x ) { return x*x*x; }
float pow4( const in float x ) { float x2 = x*x; return x2*x2; }
float max3( const in vec3 v ) { return max( max( v.x, v.y ), v.z ); }
float average( const in vec3 v ) { return dot( v, vec3( 0.3333333 ) ); }
highp float rand( const in vec2 uv ) {
	const highp float a = 12.9898, b = 78.233, c = 43758.5453;
	highp float dt = dot( uv.xy, vec2( a,b ) ), sn = mod( dt, PI );
	return fract( sin( sn ) * c );
}
#ifdef HIGH_PRECISION
	float precisionSafeLength( vec3 v ) { return length( v ); }
#else
	float precisionSafeLength( vec3 v ) {
		float maxComponent = max3( abs( v ) );
		return length( v / maxComponent ) * maxComponent;
	}
#endif
struct IncidentLight {
	vec3 color;
	vec3 direction;
	bool visible;
};
struct ReflectedLight {
	vec3 directDiffuse;
	vec3 directSpecular;
	vec3 indirectDiffuse;
	vec3 indirectSpecular;
};
#ifdef USE_ALPHAHASH
	varying vec3 vPosition;
#endif
vec3 transformDirection( in vec3 dir, in mat4 matrix ) {
	return normalize( ( matrix * vec4( dir, 0.0 ) ).xyz );
}
vec3 inverseTransformDirection( in vec3 dir, in mat4 matrix ) {
	return normalize( ( vec4( dir, 0.0 ) * matrix ).xyz );
}
mat3 transposeMat3( const in mat3 m ) {
	mat3 tmp;
	tmp[ 0 ] = vec3( m[ 0 ].x, m[ 1 ].x, m[ 2 ].x );
	tmp[ 1 ] = vec3( m[ 0 ].y, m[ 1 ].y, m[ 2 ].y );
	tmp[ 2 ] = vec3( m[ 0 ].z, m[ 1 ].z, m[ 2 ].z );
	return tmp;
}
bool isPerspectiveMatrix( mat4 m ) {
	return m[ 2 ][ 3 ] == - 1.0;
}
vec2 equirectUv( in vec3 dir ) {
	float u = atan( dir.z, dir.x ) * RECIPROCAL_PI2 + 0.5;
	float v = asin( clamp( dir.y, - 1.0, 1.0 ) ) * RECIPROCAL_PI + 0.5;
	return vec2( u, v );
}
vec3 BRDF_Lambert( const in vec3 diffuseColor ) {
	return RECIPROCAL_PI * diffuseColor;
}
vec3 F_Schlick( const in vec3 f0, const in float f90, const in float dotVH ) {
	float fresnel = exp2( ( - 5.55473 * dotVH - 6.98316 ) * dotVH );
	return f0 * ( 1.0 - fresnel ) + ( f90 * fresnel );
}
float F_Schlick( const in float f0, const in float f90, const in float dotVH ) {
	float fresnel = exp2( ( - 5.55473 * dotVH - 6.98316 ) * dotVH );
	return f0 * ( 1.0 - fresnel ) + ( f90 * fresnel );
} // validated`;
var SRt = `#ifdef ENVMAP_TYPE_CUBE_UV
	#define cubeUV_minMipLevel 4.0
	#define cubeUV_minTileSize 16.0
	float getFace( vec3 direction ) {
		vec3 absDirection = abs( direction );
		float face = - 1.0;
		if ( absDirection.x > absDirection.z ) {
			if ( absDirection.x > absDirection.y )
				face = direction.x > 0.0 ? 0.0 : 3.0;
			else
				face = direction.y > 0.0 ? 1.0 : 4.0;
		} else {
			if ( absDirection.z > absDirection.y )
				face = direction.z > 0.0 ? 2.0 : 5.0;
			else
				face = direction.y > 0.0 ? 1.0 : 4.0;
		}
		return face;
	}
	vec2 getUV( vec3 direction, float face ) {
		vec2 uv;
		if ( face == 0.0 ) {
			uv = vec2( direction.z, direction.y ) / abs( direction.x );
		} else if ( face == 1.0 ) {
			uv = vec2( - direction.x, - direction.z ) / abs( direction.y );
		} else if ( face == 2.0 ) {
			uv = vec2( - direction.x, direction.y ) / abs( direction.z );
		} else if ( face == 3.0 ) {
			uv = vec2( - direction.z, direction.y ) / abs( direction.x );
		} else if ( face == 4.0 ) {
			uv = vec2( - direction.x, direction.z ) / abs( direction.y );
		} else {
			uv = vec2( direction.x, direction.y ) / abs( direction.z );
		}
		return 0.5 * ( uv + 1.0 );
	}
	vec3 bilinearCubeUV( sampler2D envMap, vec3 direction, float mipInt ) {
		float face = getFace( direction );
		float filterInt = max( cubeUV_minMipLevel - mipInt, 0.0 );
		mipInt = max( mipInt, cubeUV_minMipLevel );
		float faceSize = exp2( mipInt );
		highp vec2 uv = getUV( direction, face ) * ( faceSize - 2.0 ) + 1.0;
		if ( face > 2.0 ) {
			uv.y += faceSize;
			face -= 3.0;
		}
		uv.x += face * faceSize;
		uv.x += filterInt * 3.0 * cubeUV_minTileSize;
		uv.y += 4.0 * ( exp2( CUBEUV_MAX_MIP ) - faceSize );
		uv.x *= CUBEUV_TEXEL_WIDTH;
		uv.y *= CUBEUV_TEXEL_HEIGHT;
		#ifdef texture2DGradEXT
			return texture2DGradEXT( envMap, uv, vec2( 0.0 ), vec2( 0.0 ) ).rgb;
		#else
			return texture2D( envMap, uv ).rgb;
		#endif
	}
	#define cubeUV_r0 1.0
	#define cubeUV_m0 - 2.0
	#define cubeUV_r1 0.8
	#define cubeUV_m1 - 1.0
	#define cubeUV_r4 0.4
	#define cubeUV_m4 2.0
	#define cubeUV_r5 0.305
	#define cubeUV_m5 3.0
	#define cubeUV_r6 0.21
	#define cubeUV_m6 4.0
	float roughnessToMip( float roughness ) {
		float mip = 0.0;
		if ( roughness >= cubeUV_r1 ) {
			mip = ( cubeUV_r0 - roughness ) * ( cubeUV_m1 - cubeUV_m0 ) / ( cubeUV_r0 - cubeUV_r1 ) + cubeUV_m0;
		} else if ( roughness >= cubeUV_r4 ) {
			mip = ( cubeUV_r1 - roughness ) * ( cubeUV_m4 - cubeUV_m1 ) / ( cubeUV_r1 - cubeUV_r4 ) + cubeUV_m1;
		} else if ( roughness >= cubeUV_r5 ) {
			mip = ( cubeUV_r4 - roughness ) * ( cubeUV_m5 - cubeUV_m4 ) / ( cubeUV_r4 - cubeUV_r5 ) + cubeUV_m4;
		} else if ( roughness >= cubeUV_r6 ) {
			mip = ( cubeUV_r5 - roughness ) * ( cubeUV_m6 - cubeUV_m5 ) / ( cubeUV_r5 - cubeUV_r6 ) + cubeUV_m5;
		} else {
			mip = - 2.0 * log2( 1.16 * roughness );		}
		return mip;
	}
	vec4 textureCubeUV( sampler2D envMap, vec3 sampleDir, float roughness ) {
		float mip = clamp( roughnessToMip( roughness ), cubeUV_m0, CUBEUV_MAX_MIP );
		float mipF = fract( mip );
		float mipInt = floor( mip );
		vec3 color0 = bilinearCubeUV( envMap, sampleDir, mipInt );
		if ( mipF == 0.0 ) {
			return vec4( color0, 1.0 );
		} else {
			vec3 color1 = bilinearCubeUV( envMap, sampleDir, mipInt + 1.0 );
			return vec4( mix( color0, color1, mipF ), 1.0 );
		}
	}
#endif`;
var BRt = `vec3 transformedNormal = objectNormal;
#ifdef USE_TANGENT
	vec3 transformedTangent = objectTangent;
#endif
#ifdef USE_BATCHING
	mat3 bm = mat3( batchingMatrix );
	transformedNormal /= vec3( dot( bm[ 0 ], bm[ 0 ] ), dot( bm[ 1 ], bm[ 1 ] ), dot( bm[ 2 ], bm[ 2 ] ) );
	transformedNormal = bm * transformedNormal;
	#ifdef USE_TANGENT
		transformedTangent = bm * transformedTangent;
	#endif
#endif
#ifdef USE_INSTANCING
	mat3 im = mat3( instanceMatrix );
	transformedNormal /= vec3( dot( im[ 0 ], im[ 0 ] ), dot( im[ 1 ], im[ 1 ] ), dot( im[ 2 ], im[ 2 ] ) );
	transformedNormal = im * transformedNormal;
	#ifdef USE_TANGENT
		transformedTangent = im * transformedTangent;
	#endif
#endif
transformedNormal = normalMatrix * transformedNormal;
#ifdef FLIP_SIDED
	transformedNormal = - transformedNormal;
#endif
#ifdef USE_TANGENT
	transformedTangent = ( modelViewMatrix * vec4( transformedTangent, 0.0 ) ).xyz;
	#ifdef FLIP_SIDED
		transformedTangent = - transformedTangent;
	#endif
#endif`;
var URt = `#ifdef USE_DISPLACEMENTMAP
	uniform sampler2D displacementMap;
	uniform float displacementScale;
	uniform float displacementBias;
#endif`;
var IRt = `#ifdef USE_DISPLACEMENTMAP
	transformed += normalize( objectNormal ) * ( texture2D( displacementMap, vDisplacementMapUv ).x * displacementScale + displacementBias );
#endif`;
var KRt = `#ifdef USE_EMISSIVEMAP
	vec4 emissiveColor = texture2D( emissiveMap, vEmissiveMapUv );
	#ifdef DECODE_VIDEO_TEXTURE_EMISSIVE
		emissiveColor = sRGBTransferEOTF( emissiveColor );
	#endif
	totalEmissiveRadiance *= emissiveColor.rgb;
#endif`;
var ERt = `#ifdef USE_EMISSIVEMAP
	uniform sampler2D emissiveMap;
#endif`;
var CRt = "gl_FragColor = linearToOutputTexel( gl_FragColor );";
var ARt = `vec4 LinearTransferOETF( in vec4 value ) {
	return value;
}
vec4 sRGBTransferEOTF( in vec4 value ) {
	return vec4( mix( pow( value.rgb * 0.9478672986 + vec3( 0.0521327014 ), vec3( 2.4 ) ), value.rgb * 0.0773993808, vec3( lessThanEqual( value.rgb, vec3( 0.04045 ) ) ) ), value.a );
}
vec4 sRGBTransferOETF( in vec4 value ) {
	return vec4( mix( pow( value.rgb, vec3( 0.41666 ) ) * 1.055 - vec3( 0.055 ), value.rgb * 12.92, vec3( lessThanEqual( value.rgb, vec3( 0.0031308 ) ) ) ), value.a );
}`;
var QRt = `#ifdef USE_ENVMAP
	#ifdef ENV_WORLDPOS
		vec3 cameraToFrag;
		if ( isOrthographic ) {
			cameraToFrag = normalize( vec3( - viewMatrix[ 0 ][ 2 ], - viewMatrix[ 1 ][ 2 ], - viewMatrix[ 2 ][ 2 ] ) );
		} else {
			cameraToFrag = normalize( vWorldPosition - cameraPosition );
		}
		vec3 worldNormal = inverseTransformDirection( normal, viewMatrix );
		#ifdef ENVMAP_MODE_REFLECTION
			vec3 reflectVec = reflect( cameraToFrag, worldNormal );
		#else
			vec3 reflectVec = refract( cameraToFrag, worldNormal, refractionRatio );
		#endif
	#else
		vec3 reflectVec = vReflect;
	#endif
	#ifdef ENVMAP_TYPE_CUBE
		vec4 envColor = textureCube( envMap, envMapRotation * vec3( flipEnvMap * reflectVec.x, reflectVec.yz ) );
	#else
		vec4 envColor = vec4( 0.0 );
	#endif
	#ifdef ENVMAP_BLENDING_MULTIPLY
		outgoingLight = mix( outgoingLight, outgoingLight * envColor.xyz, specularStrength * reflectivity );
	#elif defined( ENVMAP_BLENDING_MIX )
		outgoingLight = mix( outgoingLight, envColor.xyz, specularStrength * reflectivity );
	#elif defined( ENVMAP_BLENDING_ADD )
		outgoingLight += envColor.xyz * specularStrength * reflectivity;
	#endif
#endif`;
var _Rt = `#ifdef USE_ENVMAP
	uniform float envMapIntensity;
	uniform float flipEnvMap;
	uniform mat3 envMapRotation;
	#ifdef ENVMAP_TYPE_CUBE
		uniform samplerCube envMap;
	#else
		uniform sampler2D envMap;
	#endif

#endif`;
var $Rt = `#ifdef USE_ENVMAP
	uniform float reflectivity;
	#if defined( USE_BUMPMAP ) || defined( USE_NORMALMAP ) || defined( PHONG ) || defined( LAMBERT )
		#define ENV_WORLDPOS
	#endif
	#ifdef ENV_WORLDPOS
		varying vec3 vWorldPosition;
		uniform float refractionRatio;
	#else
		varying vec3 vReflect;
	#endif
#endif`;
var tkt = `#ifdef USE_ENVMAP
	#if defined( USE_BUMPMAP ) || defined( USE_NORMALMAP ) || defined( PHONG ) || defined( LAMBERT )
		#define ENV_WORLDPOS
	#endif
	#ifdef ENV_WORLDPOS

		varying vec3 vWorldPosition;
	#else
		varying vec3 vReflect;
		uniform float refractionRatio;
	#endif
#endif`;
var ekt = `#ifdef USE_ENVMAP
	#ifdef ENV_WORLDPOS
		vWorldPosition = worldPosition.xyz;
	#else
		vec3 cameraToVertex;
		if ( isOrthographic ) {
			cameraToVertex = normalize( vec3( - viewMatrix[ 0 ][ 2 ], - viewMatrix[ 1 ][ 2 ], - viewMatrix[ 2 ][ 2 ] ) );
		} else {
			cameraToVertex = normalize( worldPosition.xyz - cameraPosition );
		}
		vec3 worldNormal = inverseTransformDirection( transformedNormal, viewMatrix );
		#ifdef ENVMAP_MODE_REFLECTION
			vReflect = reflect( cameraToVertex, worldNormal );
		#else
			vReflect = refract( cameraToVertex, worldNormal, refractionRatio );
		#endif
	#endif
#endif`;
var fkt = `#ifdef USE_FOG
	vFogDepth = - mvPosition.z;
#endif`;
var nkt = `#ifdef USE_FOG
	varying float vFogDepth;
#endif`;
var vkt = `#ifdef USE_FOG
	#ifdef FOG_EXP2
		float fogFactor = 1.0 - exp( - fogDensity * fogDensity * vFogDepth * vFogDepth );
	#else
		float fogFactor = smoothstep( fogNear, fogFar, vFogDepth );
	#endif
	gl_FragColor.rgb = mix( gl_FragColor.rgb, fogColor, fogFactor );
#endif`;
var rkt = `#ifdef USE_FOG
	uniform vec3 fogColor;
	varying float vFogDepth;
	#ifdef FOG_EXP2
		uniform float fogDensity;
	#else
		uniform float fogNear;
		uniform float fogFar;
	#endif
#endif`;
var zkt = `#ifdef USE_GRADIENTMAP
	uniform sampler2D gradientMap;
#endif
vec3 getGradientIrradiance( vec3 normal, vec3 lightDirection ) {
	float dotNL = dot( normal, lightDirection );
	vec2 coord = vec2( dotNL * 0.5 + 0.5, 0.0 );
	#ifdef USE_GRADIENTMAP
		return vec3( texture2D( gradientMap, coord ).r );
	#else
		vec2 fw = fwidth( coord ) * 0.5;
		return mix( vec3( 0.7 ), vec3( 1.0 ), smoothstep( 0.7 - fw.x, 0.7 + fw.x, coord.x ) );
	#endif
}`;
var ukt = `#ifdef USE_LIGHTMAP
	uniform sampler2D lightMap;
	uniform float lightMapIntensity;
#endif`;
var bkt = `LambertMaterial material;
material.diffuseColor = diffuseColor.rgb;
material.specularStrength = specularStrength;`;
var akt = `varying vec3 vViewPosition;
struct LambertMaterial {
	vec3 diffuseColor;
	float specularStrength;
};
void RE_Direct_Lambert( const in IncidentLight directLight, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in LambertMaterial material, inout ReflectedLight reflectedLight ) {
	float dotNL = saturate( dot( geometryNormal, directLight.direction ) );
	vec3 irradiance = dotNL * directLight.color;
	reflectedLight.directDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
}
void RE_IndirectDiffuse_Lambert( const in vec3 irradiance, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in LambertMaterial material, inout ReflectedLight reflectedLight ) {
	reflectedLight.indirectDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
}
#define RE_Direct				RE_Direct_Lambert
#define RE_IndirectDiffuse		RE_IndirectDiffuse_Lambert`;
var Pkt = `uniform bool receiveShadow;
uniform vec3 ambientLightColor;
#if defined( USE_LIGHT_PROBES )
	uniform vec3 lightProbe[ 9 ];
#endif
vec3 shGetIrradianceAt( in vec3 normal, in vec3 shCoefficients[ 9 ] ) {
	float x = normal.x, y = normal.y, z = normal.z;
	vec3 result = shCoefficients[ 0 ] * 0.886227;
	result += shCoefficients[ 1 ] * 2.0 * 0.511664 * y;
	result += shCoefficients[ 2 ] * 2.0 * 0.511664 * z;
	result += shCoefficients[ 3 ] * 2.0 * 0.511664 * x;
	result += shCoefficients[ 4 ] * 2.0 * 0.429043 * x * y;
	result += shCoefficients[ 5 ] * 2.0 * 0.429043 * y * z;
	result += shCoefficients[ 6 ] * ( 0.743125 * z * z - 0.247708 );
	result += shCoefficients[ 7 ] * 2.0 * 0.429043 * x * z;
	result += shCoefficients[ 8 ] * 0.429043 * ( x * x - y * y );
	return result;
}
vec3 getLightProbeIrradiance( const in vec3 lightProbe[ 9 ], const in vec3 normal ) {
	vec3 worldNormal = inverseTransformDirection( normal, viewMatrix );
	vec3 irradiance = shGetIrradianceAt( worldNormal, lightProbe );
	return irradiance;
}
vec3 getAmbientLightIrradiance( const in vec3 ambientLightColor ) {
	vec3 irradiance = ambientLightColor;
	return irradiance;
}
float getDistanceAttenuation( const in float lightDistance, const in float cutoffDistance, const in float decayExponent ) {
	float distanceFalloff = 1.0 / max( pow( lightDistance, decayExponent ), 0.01 );
	if ( cutoffDistance > 0.0 ) {
		distanceFalloff *= pow2( saturate( 1.0 - pow4( lightDistance / cutoffDistance ) ) );
	}
	return distanceFalloff;
}
float getSpotAttenuation( const in float coneCosine, const in float penumbraCosine, const in float angleCosine ) {
	return smoothstep( coneCosine, penumbraCosine, angleCosine );
}
#if NUM_DIR_LIGHTS > 0
	struct DirectionalLight {
		vec3 direction;
		vec3 color;
	};
	uniform DirectionalLight directionalLights[ NUM_DIR_LIGHTS ];
	void getDirectionalLightInfo( const in DirectionalLight directionalLight, out IncidentLight light ) {
		light.color = directionalLight.color;
		light.direction = directionalLight.direction;
		light.visible = true;
	}
#endif
#if NUM_POINT_LIGHTS > 0
	struct PointLight {
		vec3 position;
		vec3 color;
		float distance;
		float decay;
	};
	uniform PointLight pointLights[ NUM_POINT_LIGHTS ];
	void getPointLightInfo( const in PointLight pointLight, const in vec3 geometryPosition, out IncidentLight light ) {
		vec3 lVector = pointLight.position - geometryPosition;
		light.direction = normalize( lVector );
		float lightDistance = length( lVector );
		light.color = pointLight.color;
		light.color *= getDistanceAttenuation( lightDistance, pointLight.distance, pointLight.decay );
		light.visible = ( light.color != vec3( 0.0 ) );
	}
#endif
#if NUM_SPOT_LIGHTS > 0
	struct SpotLight {
		vec3 position;
		vec3 direction;
		vec3 color;
		float distance;
		float decay;
		float coneCos;
		float penumbraCos;
	};
	uniform SpotLight spotLights[ NUM_SPOT_LIGHTS ];
	void getSpotLightInfo( const in SpotLight spotLight, const in vec3 geometryPosition, out IncidentLight light ) {
		vec3 lVector = spotLight.position - geometryPosition;
		light.direction = normalize( lVector );
		float angleCos = dot( light.direction, spotLight.direction );
		float spotAttenuation = getSpotAttenuation( spotLight.coneCos, spotLight.penumbraCos, angleCos );
		if ( spotAttenuation > 0.0 ) {
			float lightDistance = length( lVector );
			light.color = spotLight.color * spotAttenuation;
			light.color *= getDistanceAttenuation( lightDistance, spotLight.distance, spotLight.decay );
			light.visible = ( light.color != vec3( 0.0 ) );
		} else {
			light.color = vec3( 0.0 );
			light.visible = false;
		}
	}
#endif
#if NUM_RECT_AREA_LIGHTS > 0
	struct RectAreaLight {
		vec3 color;
		vec3 position;
		vec3 halfWidth;
		vec3 halfHeight;
	};
	uniform sampler2D ltc_1;	uniform sampler2D ltc_2;
	uniform RectAreaLight rectAreaLights[ NUM_RECT_AREA_LIGHTS ];
#endif
#if NUM_HEMI_LIGHTS > 0
	struct HemisphereLight {
		vec3 direction;
		vec3 skyColor;
		vec3 groundColor;
	};
	uniform HemisphereLight hemisphereLights[ NUM_HEMI_LIGHTS ];
	vec3 getHemisphereLightIrradiance( const in HemisphereLight hemiLight, const in vec3 normal ) {
		float dotNL = dot( normal, hemiLight.direction );
		float hemiDiffuseWeight = 0.5 * dotNL + 0.5;
		vec3 irradiance = mix( hemiLight.groundColor, hemiLight.skyColor, hemiDiffuseWeight );
		return irradiance;
	}
#endif`;
var skt = `#ifdef USE_ENVMAP
	vec3 getIBLIrradiance( const in vec3 normal ) {
		#ifdef ENVMAP_TYPE_CUBE_UV
			vec3 worldNormal = inverseTransformDirection( normal, viewMatrix );
			vec4 envMapColor = textureCubeUV( envMap, envMapRotation * worldNormal, 1.0 );
			return PI * envMapColor.rgb * envMapIntensity;
		#else
			return vec3( 0.0 );
		#endif
	}
	vec3 getIBLRadiance( const in vec3 viewDir, const in vec3 normal, const in float roughness ) {
		#ifdef ENVMAP_TYPE_CUBE_UV
			vec3 reflectVec = reflect( - viewDir, normal );
			reflectVec = normalize( mix( reflectVec, normal, roughness * roughness) );
			reflectVec = inverseTransformDirection( reflectVec, viewMatrix );
			vec4 envMapColor = textureCubeUV( envMap, envMapRotation * reflectVec, roughness );
			return envMapColor.rgb * envMapIntensity;
		#else
			return vec3( 0.0 );
		#endif
	}
	#ifdef USE_ANISOTROPY
		vec3 getIBLAnisotropyRadiance( const in vec3 viewDir, const in vec3 normal, const in float roughness, const in vec3 bitangent, const in float anisotropy ) {
			#ifdef ENVMAP_TYPE_CUBE_UV
				vec3 bentNormal = cross( bitangent, viewDir );
				bentNormal = normalize( cross( bentNormal, bitangent ) );
				bentNormal = normalize( mix( bentNormal, normal, pow2( pow2( 1.0 - anisotropy * ( 1.0 - roughness ) ) ) ) );
				return getIBLRadiance( viewDir, bentNormal, roughness );
			#else
				return vec3( 0.0 );
			#endif
		}
	#endif
#endif`;
var dkt = `ToonMaterial material;
material.diffuseColor = diffuseColor.rgb;`;
var ckt = `varying vec3 vViewPosition;
struct ToonMaterial {
	vec3 diffuseColor;
};
void RE_Direct_Toon( const in IncidentLight directLight, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in ToonMaterial material, inout ReflectedLight reflectedLight ) {
	vec3 irradiance = getGradientIrradiance( geometryNormal, directLight.direction ) * directLight.color;
	reflectedLight.directDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
}
void RE_IndirectDiffuse_Toon( const in vec3 irradiance, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in ToonMaterial material, inout ReflectedLight reflectedLight ) {
	reflectedLight.indirectDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
}
#define RE_Direct				RE_Direct_Toon
#define RE_IndirectDiffuse		RE_IndirectDiffuse_Toon`;
var lkt = `BlinnPhongMaterial material;
material.diffuseColor = diffuseColor.rgb;
material.specularColor = specular;
material.specularShininess = shininess;
material.specularStrength = specularStrength;`;
var Xkt = `varying vec3 vViewPosition;
struct BlinnPhongMaterial {
	vec3 diffuseColor;
	vec3 specularColor;
	float specularShininess;
	float specularStrength;
};
void RE_Direct_BlinnPhong( const in IncidentLight directLight, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in BlinnPhongMaterial material, inout ReflectedLight reflectedLight ) {
	float dotNL = saturate( dot( geometryNormal, directLight.direction ) );
	vec3 irradiance = dotNL * directLight.color;
	reflectedLight.directDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
	reflectedLight.directSpecular += irradiance * BRDF_BlinnPhong( directLight.direction, geometryViewDir, geometryNormal, material.specularColor, material.specularShininess ) * material.specularStrength;
}
void RE_IndirectDiffuse_BlinnPhong( const in vec3 irradiance, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in BlinnPhongMaterial material, inout ReflectedLight reflectedLight ) {
	reflectedLight.indirectDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
}
#define RE_Direct				RE_Direct_BlinnPhong
#define RE_IndirectDiffuse		RE_IndirectDiffuse_BlinnPhong`;
var Wkt = `PhysicalMaterial material;
material.diffuseColor = diffuseColor.rgb * ( 1.0 - metalnessFactor );
vec3 dxy = max( abs( dFdx( nonPerturbedNormal ) ), abs( dFdy( nonPerturbedNormal ) ) );
float geometryRoughness = max( max( dxy.x, dxy.y ), dxy.z );
material.roughness = max( roughnessFactor, 0.0525 );material.roughness += geometryRoughness;
material.roughness = min( material.roughness, 1.0 );
#ifdef IOR
	material.ior = ior;
	#ifdef USE_SPECULAR
		float specularIntensityFactor = specularIntensity;
		vec3 specularColorFactor = specularColor;
		#ifdef USE_SPECULAR_COLORMAP
			specularColorFactor *= texture2D( specularColorMap, vSpecularColorMapUv ).rgb;
		#endif
		#ifdef USE_SPECULAR_INTENSITYMAP
			specularIntensityFactor *= texture2D( specularIntensityMap, vSpecularIntensityMapUv ).a;
		#endif
		material.specularF90 = mix( specularIntensityFactor, 1.0, metalnessFactor );
	#else
		float specularIntensityFactor = 1.0;
		vec3 specularColorFactor = vec3( 1.0 );
		material.specularF90 = 1.0;
	#endif
	material.specularColor = mix( min( pow2( ( material.ior - 1.0 ) / ( material.ior + 1.0 ) ) * specularColorFactor, vec3( 1.0 ) ) * specularIntensityFactor, diffuseColor.rgb, metalnessFactor );
#else
	material.specularColor = mix( vec3( 0.04 ), diffuseColor.rgb, metalnessFactor );
	material.specularF90 = 1.0;
#endif
#ifdef USE_CLEARCOAT
	material.clearcoat = clearcoat;
	material.clearcoatRoughness = clearcoatRoughness;
	material.clearcoatF0 = vec3( 0.04 );
	material.clearcoatF90 = 1.0;
	#ifdef USE_CLEARCOATMAP
		material.clearcoat *= texture2D( clearcoatMap, vClearcoatMapUv ).x;
	#endif
	#ifdef USE_CLEARCOAT_ROUGHNESSMAP
		material.clearcoatRoughness *= texture2D( clearcoatRoughnessMap, vClearcoatRoughnessMapUv ).y;
	#endif
	material.clearcoat = saturate( material.clearcoat );	material.clearcoatRoughness = max( material.clearcoatRoughness, 0.0525 );
	material.clearcoatRoughness += geometryRoughness;
	material.clearcoatRoughness = min( material.clearcoatRoughness, 1.0 );
#endif
#ifdef USE_DISPERSION
	material.dispersion = dispersion;
#endif
#ifdef USE_IRIDESCENCE
	material.iridescence = iridescence;
	material.iridescenceIOR = iridescenceIOR;
	#ifdef USE_IRIDESCENCEMAP
		material.iridescence *= texture2D( iridescenceMap, vIridescenceMapUv ).r;
	#endif
	#ifdef USE_IRIDESCENCE_THICKNESSMAP
		material.iridescenceThickness = (iridescenceThicknessMaximum - iridescenceThicknessMinimum) * texture2D( iridescenceThicknessMap, vIridescenceThicknessMapUv ).g + iridescenceThicknessMinimum;
	#else
		material.iridescenceThickness = iridescenceThicknessMaximum;
	#endif
#endif
#ifdef USE_SHEEN
	material.sheenColor = sheenColor;
	#ifdef USE_SHEEN_COLORMAP
		material.sheenColor *= texture2D( sheenColorMap, vSheenColorMapUv ).rgb;
	#endif
	material.sheenRoughness = clamp( sheenRoughness, 0.07, 1.0 );
	#ifdef USE_SHEEN_ROUGHNESSMAP
		material.sheenRoughness *= texture2D( sheenRoughnessMap, vSheenRoughnessMapUv ).a;
	#endif
#endif
#ifdef USE_ANISOTROPY
	#ifdef USE_ANISOTROPYMAP
		mat2 anisotropyMat = mat2( anisotropyVector.x, anisotropyVector.y, - anisotropyVector.y, anisotropyVector.x );
		vec3 anisotropyPolar = texture2D( anisotropyMap, vAnisotropyMapUv ).rgb;
		vec2 anisotropyV = anisotropyMat * normalize( 2.0 * anisotropyPolar.rg - vec2( 1.0 ) ) * anisotropyPolar.b;
	#else
		vec2 anisotropyV = anisotropyVector;
	#endif
	material.anisotropy = length( anisotropyV );
	if( material.anisotropy == 0.0 ) {
		anisotropyV = vec2( 1.0, 0.0 );
	} else {
		anisotropyV /= material.anisotropy;
		material.anisotropy = saturate( material.anisotropy );
	}
	material.alphaT = mix( pow2( material.roughness ), 1.0, pow2( material.anisotropy ) );
	material.anisotropyT = tbn[ 0 ] * anisotropyV.x + tbn[ 1 ] * anisotropyV.y;
	material.anisotropyB = tbn[ 1 ] * anisotropyV.x - tbn[ 0 ] * anisotropyV.y;
#endif`;
var ykt = `struct PhysicalMaterial {
	vec3 diffuseColor;
	float roughness;
	vec3 specularColor;
	float specularF90;
	float dispersion;
	#ifdef USE_CLEARCOAT
		float clearcoat;
		float clearcoatRoughness;
		vec3 clearcoatF0;
		float clearcoatF90;
	#endif
	#ifdef USE_IRIDESCENCE
		float iridescence;
		float iridescenceIOR;
		float iridescenceThickness;
		vec3 iridescenceFresnel;
		vec3 iridescenceF0;
	#endif
	#ifdef USE_SHEEN
		vec3 sheenColor;
		float sheenRoughness;
	#endif
	#ifdef IOR
		float ior;
	#endif
	#ifdef USE_TRANSMISSION
		float transmission;
		float transmissionAlpha;
		float thickness;
		float attenuationDistance;
		vec3 attenuationColor;
	#endif
	#ifdef USE_ANISOTROPY
		float anisotropy;
		float alphaT;
		vec3 anisotropyT;
		vec3 anisotropyB;
	#endif
};
vec3 clearcoatSpecularDirect = vec3( 0.0 );
vec3 clearcoatSpecularIndirect = vec3( 0.0 );
vec3 sheenSpecularDirect = vec3( 0.0 );
vec3 sheenSpecularIndirect = vec3(0.0 );
vec3 Schlick_to_F0( const in vec3 f, const in float f90, const in float dotVH ) {
    float x = clamp( 1.0 - dotVH, 0.0, 1.0 );
    float x2 = x * x;
    float x5 = clamp( x * x2 * x2, 0.0, 0.9999 );
    return ( f - vec3( f90 ) * x5 ) / ( 1.0 - x5 );
}
float V_GGX_SmithCorrelated( const in float alpha, const in float dotNL, const in float dotNV ) {
	float a2 = pow2( alpha );
	float gv = dotNL * sqrt( a2 + ( 1.0 - a2 ) * pow2( dotNV ) );
	float gl = dotNV * sqrt( a2 + ( 1.0 - a2 ) * pow2( dotNL ) );
	return 0.5 / max( gv + gl, EPSILON );
}
float D_GGX( const in float alpha, const in float dotNH ) {
	float a2 = pow2( alpha );
	float denom = pow2( dotNH ) * ( a2 - 1.0 ) + 1.0;
	return RECIPROCAL_PI * a2 / pow2( denom );
}
#ifdef USE_ANISOTROPY
	float V_GGX_SmithCorrelated_Anisotropic( const in float alphaT, const in float alphaB, const in float dotTV, const in float dotBV, const in float dotTL, const in float dotBL, const in float dotNV, const in float dotNL ) {
		float gv = dotNL * length( vec3( alphaT * dotTV, alphaB * dotBV, dotNV ) );
		float gl = dotNV * length( vec3( alphaT * dotTL, alphaB * dotBL, dotNL ) );
		float v = 0.5 / ( gv + gl );
		return saturate(v);
	}
	float D_GGX_Anisotropic( const in float alphaT, const in float alphaB, const in float dotNH, const in float dotTH, const in float dotBH ) {
		float a2 = alphaT * alphaB;
		highp vec3 v = vec3( alphaB * dotTH, alphaT * dotBH, a2 * dotNH );
		highp float v2 = dot( v, v );
		float w2 = a2 / v2;
		return RECIPROCAL_PI * a2 * pow2 ( w2 );
	}
#endif
#ifdef USE_CLEARCOAT
	vec3 BRDF_GGX_Clearcoat( const in vec3 lightDir, const in vec3 viewDir, const in vec3 normal, const in PhysicalMaterial material) {
		vec3 f0 = material.clearcoatF0;
		float f90 = material.clearcoatF90;
		float roughness = material.clearcoatRoughness;
		float alpha = pow2( roughness );
		vec3 halfDir = normalize( lightDir + viewDir );
		float dotNL = saturate( dot( normal, lightDir ) );
		float dotNV = saturate( dot( normal, viewDir ) );
		float dotNH = saturate( dot( normal, halfDir ) );
		float dotVH = saturate( dot( viewDir, halfDir ) );
		vec3 F = F_Schlick( f0, f90, dotVH );
		float V = V_GGX_SmithCorrelated( alpha, dotNL, dotNV );
		float D = D_GGX( alpha, dotNH );
		return F * ( V * D );
	}
#endif
vec3 BRDF_GGX( const in vec3 lightDir, const in vec3 viewDir, const in vec3 normal, const in PhysicalMaterial material ) {
	vec3 f0 = material.specularColor;
	float f90 = material.specularF90;
	float roughness = material.roughness;
	float alpha = pow2( roughness );
	vec3 halfDir = normalize( lightDir + viewDir );
	float dotNL = saturate( dot( normal, lightDir ) );
	float dotNV = saturate( dot( normal, viewDir ) );
	float dotNH = saturate( dot( normal, halfDir ) );
	float dotVH = saturate( dot( viewDir, halfDir ) );
	vec3 F = F_Schlick( f0, f90, dotVH );
	#ifdef USE_IRIDESCENCE
		F = mix( F, material.iridescenceFresnel, material.iridescence );
	#endif
	#ifdef USE_ANISOTROPY
		float dotTL = dot( material.anisotropyT, lightDir );
		float dotTV = dot( material.anisotropyT, viewDir );
		float dotTH = dot( material.anisotropyT, halfDir );
		float dotBL = dot( material.anisotropyB, lightDir );
		float dotBV = dot( material.anisotropyB, viewDir );
		float dotBH = dot( material.anisotropyB, halfDir );
		float V = V_GGX_SmithCorrelated_Anisotropic( material.alphaT, alpha, dotTV, dotBV, dotTL, dotBL, dotNV, dotNL );
		float D = D_GGX_Anisotropic( material.alphaT, alpha, dotNH, dotTH, dotBH );
	#else
		float V = V_GGX_SmithCorrelated( alpha, dotNL, dotNV );
		float D = D_GGX( alpha, dotNH );
	#endif
	return F * ( V * D );
}
vec2 LTC_Uv( const in vec3 N, const in vec3 V, const in float roughness ) {
	const float LUT_SIZE = 64.0;
	const float LUT_SCALE = ( LUT_SIZE - 1.0 ) / LUT_SIZE;
	const float LUT_BIAS = 0.5 / LUT_SIZE;
	float dotNV = saturate( dot( N, V ) );
	vec2 uv = vec2( roughness, sqrt( 1.0 - dotNV ) );
	uv = uv * LUT_SCALE + LUT_BIAS;
	return uv;
}
float LTC_ClippedSphereFormFactor( const in vec3 f ) {
	float l = length( f );
	return max( ( l * l + f.z ) / ( l + 1.0 ), 0.0 );
}
vec3 LTC_EdgeVectorFormFactor( const in vec3 v1, const in vec3 v2 ) {
	float x = dot( v1, v2 );
	float y = abs( x );
	float a = 0.8543985 + ( 0.4965155 + 0.0145206 * y ) * y;
	float b = 3.4175940 + ( 4.1616724 + y ) * y;
	float v = a / b;
	float theta_sintheta = ( x > 0.0 ) ? v : 0.5 * inversesqrt( max( 1.0 - x * x, 1e-7 ) ) - v;
	return cross( v1, v2 ) * theta_sintheta;
}
vec3 LTC_Evaluate( const in vec3 N, const in vec3 V, const in vec3 P, const in mat3 mInv, const in vec3 rectCoords[ 4 ] ) {
	vec3 v1 = rectCoords[ 1 ] - rectCoords[ 0 ];
	vec3 v2 = rectCoords[ 3 ] - rectCoords[ 0 ];
	vec3 lightNormal = cross( v1, v2 );
	if( dot( lightNormal, P - rectCoords[ 0 ] ) < 0.0 ) return vec3( 0.0 );
	vec3 T1, T2;
	T1 = normalize( V - N * dot( V, N ) );
	T2 = - cross( N, T1 );
	mat3 mat = mInv * transposeMat3( mat3( T1, T2, N ) );
	vec3 coords[ 4 ];
	coords[ 0 ] = mat * ( rectCoords[ 0 ] - P );
	coords[ 1 ] = mat * ( rectCoords[ 1 ] - P );
	coords[ 2 ] = mat * ( rectCoords[ 2 ] - P );
	coords[ 3 ] = mat * ( rectCoords[ 3 ] - P );
	coords[ 0 ] = normalize( coords[ 0 ] );
	coords[ 1 ] = normalize( coords[ 1 ] );
	coords[ 2 ] = normalize( coords[ 2 ] );
	coords[ 3 ] = normalize( coords[ 3 ] );
	vec3 vectorFormFactor = vec3( 0.0 );
	vectorFormFactor += LTC_EdgeVectorFormFactor( coords[ 0 ], coords[ 1 ] );
	vectorFormFactor += LTC_EdgeVectorFormFactor( coords[ 1 ], coords[ 2 ] );
	vectorFormFactor += LTC_EdgeVectorFormFactor( coords[ 2 ], coords[ 3 ] );
	vectorFormFactor += LTC_EdgeVectorFormFactor( coords[ 3 ], coords[ 0 ] );
	float result = LTC_ClippedSphereFormFactor( vectorFormFactor );
	return vec3( result );
}
#if defined( USE_SHEEN )
float D_Charlie( float roughness, float dotNH ) {
	float alpha = pow2( roughness );
	float invAlpha = 1.0 / alpha;
	float cos2h = dotNH * dotNH;
	float sin2h = max( 1.0 - cos2h, 0.0078125 );
	return ( 2.0 + invAlpha ) * pow( sin2h, invAlpha * 0.5 ) / ( 2.0 * PI );
}
float V_Neubelt( float dotNV, float dotNL ) {
	return saturate( 1.0 / ( 4.0 * ( dotNL + dotNV - dotNL * dotNV ) ) );
}
vec3 BRDF_Sheen( const in vec3 lightDir, const in vec3 viewDir, const in vec3 normal, vec3 sheenColor, const in float sheenRoughness ) {
	vec3 halfDir = normalize( lightDir + viewDir );
	float dotNL = saturate( dot( normal, lightDir ) );
	float dotNV = saturate( dot( normal, viewDir ) );
	float dotNH = saturate( dot( normal, halfDir ) );
	float D = D_Charlie( sheenRoughness, dotNH );
	float V = V_Neubelt( dotNV, dotNL );
	return sheenColor * ( D * V );
}
#endif
float IBLSheenBRDF( const in vec3 normal, const in vec3 viewDir, const in float roughness ) {
	float dotNV = saturate( dot( normal, viewDir ) );
	float r2 = roughness * roughness;
	float a = roughness < 0.25 ? -339.2 * r2 + 161.4 * roughness - 25.9 : -8.48 * r2 + 14.3 * roughness - 9.95;
	float b = roughness < 0.25 ? 44.0 * r2 - 23.7 * roughness + 3.26 : 1.97 * r2 - 3.27 * roughness + 0.72;
	float DG = exp( a * dotNV + b ) + ( roughness < 0.25 ? 0.0 : 0.1 * ( roughness - 0.25 ) );
	return saturate( DG * RECIPROCAL_PI );
}
vec2 DFGApprox( const in vec3 normal, const in vec3 viewDir, const in float roughness ) {
	float dotNV = saturate( dot( normal, viewDir ) );
	const vec4 c0 = vec4( - 1, - 0.0275, - 0.572, 0.022 );
	const vec4 c1 = vec4( 1, 0.0425, 1.04, - 0.04 );
	vec4 r = roughness * c0 + c1;
	float a004 = min( r.x * r.x, exp2( - 9.28 * dotNV ) ) * r.x + r.y;
	vec2 fab = vec2( - 1.04, 1.04 ) * a004 + r.zw;
	return fab;
}
vec3 EnvironmentBRDF( const in vec3 normal, const in vec3 viewDir, const in vec3 specularColor, const in float specularF90, const in float roughness ) {
	vec2 fab = DFGApprox( normal, viewDir, roughness );
	return specularColor * fab.x + specularF90 * fab.y;
}
#ifdef USE_IRIDESCENCE
void computeMultiscatteringIridescence( const in vec3 normal, const in vec3 viewDir, const in vec3 specularColor, const in float specularF90, const in float iridescence, const in vec3 iridescenceF0, const in float roughness, inout vec3 singleScatter, inout vec3 multiScatter ) {
#else
void computeMultiscattering( const in vec3 normal, const in vec3 viewDir, const in vec3 specularColor, const in float specularF90, const in float roughness, inout vec3 singleScatter, inout vec3 multiScatter ) {
#endif
	vec2 fab = DFGApprox( normal, viewDir, roughness );
	#ifdef USE_IRIDESCENCE
		vec3 Fr = mix( specularColor, iridescenceF0, iridescence );
	#else
		vec3 Fr = specularColor;
	#endif
	vec3 FssEss = Fr * fab.x + specularF90 * fab.y;
	float Ess = fab.x + fab.y;
	float Ems = 1.0 - Ess;
	vec3 Favg = Fr + ( 1.0 - Fr ) * 0.047619;	vec3 Fms = FssEss * Favg / ( 1.0 - Ems * Favg );
	singleScatter += FssEss;
	multiScatter += Fms * Ems;
}
#if NUM_RECT_AREA_LIGHTS > 0
	void RE_Direct_RectArea_Physical( const in RectAreaLight rectAreaLight, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in PhysicalMaterial material, inout ReflectedLight reflectedLight ) {
		vec3 normal = geometryNormal;
		vec3 viewDir = geometryViewDir;
		vec3 position = geometryPosition;
		vec3 lightPos = rectAreaLight.position;
		vec3 halfWidth = rectAreaLight.halfWidth;
		vec3 halfHeight = rectAreaLight.halfHeight;
		vec3 lightColor = rectAreaLight.color;
		float roughness = material.roughness;
		vec3 rectCoords[ 4 ];
		rectCoords[ 0 ] = lightPos + halfWidth - halfHeight;		rectCoords[ 1 ] = lightPos - halfWidth - halfHeight;
		rectCoords[ 2 ] = lightPos - halfWidth + halfHeight;
		rectCoords[ 3 ] = lightPos + halfWidth + halfHeight;
		vec2 uv = LTC_Uv( normal, viewDir, roughness );
		vec4 t1 = texture2D( ltc_1, uv );
		vec4 t2 = texture2D( ltc_2, uv );
		mat3 mInv = mat3(
			vec3( t1.x, 0, t1.y ),
			vec3(    0, 1,    0 ),
			vec3( t1.z, 0, t1.w )
		);
		vec3 fresnel = ( material.specularColor * t2.x + ( vec3( 1.0 ) - material.specularColor ) * t2.y );
		reflectedLight.directSpecular += lightColor * fresnel * LTC_Evaluate( normal, viewDir, position, mInv, rectCoords );
		reflectedLight.directDiffuse += lightColor * material.diffuseColor * LTC_Evaluate( normal, viewDir, position, mat3( 1.0 ), rectCoords );
	}
#endif
void RE_Direct_Physical( const in IncidentLight directLight, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in PhysicalMaterial material, inout ReflectedLight reflectedLight ) {
	float dotNL = saturate( dot( geometryNormal, directLight.direction ) );
	vec3 irradiance = dotNL * directLight.color;
	#ifdef USE_CLEARCOAT
		float dotNLcc = saturate( dot( geometryClearcoatNormal, directLight.direction ) );
		vec3 ccIrradiance = dotNLcc * directLight.color;
		clearcoatSpecularDirect += ccIrradiance * BRDF_GGX_Clearcoat( directLight.direction, geometryViewDir, geometryClearcoatNormal, material );
	#endif
	#ifdef USE_SHEEN
		sheenSpecularDirect += irradiance * BRDF_Sheen( directLight.direction, geometryViewDir, geometryNormal, material.sheenColor, material.sheenRoughness );
	#endif
	reflectedLight.directSpecular += irradiance * BRDF_GGX( directLight.direction, geometryViewDir, geometryNormal, material );
	reflectedLight.directDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
}
void RE_IndirectDiffuse_Physical( const in vec3 irradiance, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in PhysicalMaterial material, inout ReflectedLight reflectedLight ) {
	reflectedLight.indirectDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
}
void RE_IndirectSpecular_Physical( const in vec3 radiance, const in vec3 irradiance, const in vec3 clearcoatRadiance, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in PhysicalMaterial material, inout ReflectedLight reflectedLight) {
	#ifdef USE_CLEARCOAT
		clearcoatSpecularIndirect += clearcoatRadiance * EnvironmentBRDF( geometryClearcoatNormal, geometryViewDir, material.clearcoatF0, material.clearcoatF90, material.clearcoatRoughness );
	#endif
	#ifdef USE_SHEEN
		sheenSpecularIndirect += irradiance * material.sheenColor * IBLSheenBRDF( geometryNormal, geometryViewDir, material.sheenRoughness );
	#endif
	vec3 singleScattering = vec3( 0.0 );
	vec3 multiScattering = vec3( 0.0 );
	vec3 cosineWeightedIrradiance = irradiance * RECIPROCAL_PI;
	#ifdef USE_IRIDESCENCE
		computeMultiscatteringIridescence( geometryNormal, geometryViewDir, material.specularColor, material.specularF90, material.iridescence, material.iridescenceFresnel, material.roughness, singleScattering, multiScattering );
	#else
		computeMultiscattering( geometryNormal, geometryViewDir, material.specularColor, material.specularF90, material.roughness, singleScattering, multiScattering );
	#endif
	vec3 totalScattering = singleScattering + multiScattering;
	vec3 diffuse = material.diffuseColor * ( 1.0 - max( max( totalScattering.r, totalScattering.g ), totalScattering.b ) );
	reflectedLight.indirectSpecular += radiance * singleScattering;
	reflectedLight.indirectSpecular += multiScattering * cosineWeightedIrradiance;
	reflectedLight.indirectDiffuse += diffuse * cosineWeightedIrradiance;
}
#define RE_Direct				RE_Direct_Physical
#define RE_Direct_RectArea		RE_Direct_RectArea_Physical
#define RE_IndirectDiffuse		RE_IndirectDiffuse_Physical
#define RE_IndirectSpecular		RE_IndirectSpecular_Physical
float computeSpecularOcclusion( const in float dotNV, const in float ambientOcclusion, const in float roughness ) {
	return saturate( pow( dotNV + ambientOcclusion, exp2( - 16.0 * roughness - 1.0 ) ) - 1.0 + ambientOcclusion );
}`;
var Zkt = `
vec3 geometryPosition = - vViewPosition;
vec3 geometryNormal = normal;
vec3 geometryViewDir = ( isOrthographic ) ? vec3( 0, 0, 1 ) : normalize( vViewPosition );
vec3 geometryClearcoatNormal = vec3( 0.0 );
#ifdef USE_CLEARCOAT
	geometryClearcoatNormal = clearcoatNormal;
#endif
#ifdef USE_IRIDESCENCE
	float dotNVi = saturate( dot( normal, geometryViewDir ) );
	if ( material.iridescenceThickness == 0.0 ) {
		material.iridescence = 0.0;
	} else {
		material.iridescence = saturate( material.iridescence );
	}
	if ( material.iridescence > 0.0 ) {
		material.iridescenceFresnel = evalIridescence( 1.0, material.iridescenceIOR, dotNVi, material.iridescenceThickness, material.specularColor );
		material.iridescenceF0 = Schlick_to_F0( material.iridescenceFresnel, 1.0, dotNVi );
	}
#endif
IncidentLight directLight;
#if ( NUM_POINT_LIGHTS > 0 ) && defined( RE_Direct )
	PointLight pointLight;
	#if defined( USE_SHADOWMAP ) && NUM_POINT_LIGHT_SHADOWS > 0
	PointLightShadow pointLightShadow;
	#endif
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_POINT_LIGHTS; i ++ ) {
		pointLight = pointLights[ i ];
		getPointLightInfo( pointLight, geometryPosition, directLight );
		#if defined( USE_SHADOWMAP ) && ( UNROLLED_LOOP_INDEX < NUM_POINT_LIGHT_SHADOWS )
		pointLightShadow = pointLightShadows[ i ];
		directLight.color *= ( directLight.visible && receiveShadow ) ? getPointShadow( pointShadowMap[ i ], pointLightShadow.shadowMapSize, pointLightShadow.shadowIntensity, pointLightShadow.shadowBias, pointLightShadow.shadowRadius, vPointShadowCoord[ i ], pointLightShadow.shadowCameraNear, pointLightShadow.shadowCameraFar ) : 1.0;
		#endif
		RE_Direct( directLight, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight );
	}
	#pragma unroll_loop_end
#endif
#if ( NUM_SPOT_LIGHTS > 0 ) && defined( RE_Direct )
	SpotLight spotLight;
	vec4 spotColor;
	vec3 spotLightCoord;
	bool inSpotLightMap;
	#if defined( USE_SHADOWMAP ) && NUM_SPOT_LIGHT_SHADOWS > 0
	SpotLightShadow spotLightShadow;
	#endif
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_SPOT_LIGHTS; i ++ ) {
		spotLight = spotLights[ i ];
		getSpotLightInfo( spotLight, geometryPosition, directLight );
		#if ( UNROLLED_LOOP_INDEX < NUM_SPOT_LIGHT_SHADOWS_WITH_MAPS )
		#define SPOT_LIGHT_MAP_INDEX UNROLLED_LOOP_INDEX
		#elif ( UNROLLED_LOOP_INDEX < NUM_SPOT_LIGHT_SHADOWS )
		#define SPOT_LIGHT_MAP_INDEX NUM_SPOT_LIGHT_MAPS
		#else
		#define SPOT_LIGHT_MAP_INDEX ( UNROLLED_LOOP_INDEX - NUM_SPOT_LIGHT_SHADOWS + NUM_SPOT_LIGHT_SHADOWS_WITH_MAPS )
		#endif
		#if ( SPOT_LIGHT_MAP_INDEX < NUM_SPOT_LIGHT_MAPS )
			spotLightCoord = vSpotLightCoord[ i ].xyz / vSpotLightCoord[ i ].w;
			inSpotLightMap = all( lessThan( abs( spotLightCoord * 2. - 1. ), vec3( 1.0 ) ) );
			spotColor = texture2D( spotLightMap[ SPOT_LIGHT_MAP_INDEX ], spotLightCoord.xy );
			directLight.color = inSpotLightMap ? directLight.color * spotColor.rgb : directLight.color;
		#endif
		#undef SPOT_LIGHT_MAP_INDEX
		#if defined( USE_SHADOWMAP ) && ( UNROLLED_LOOP_INDEX < NUM_SPOT_LIGHT_SHADOWS )
		spotLightShadow = spotLightShadows[ i ];
		directLight.color *= ( directLight.visible && receiveShadow ) ? getShadow( spotShadowMap[ i ], spotLightShadow.shadowMapSize, spotLightShadow.shadowIntensity, spotLightShadow.shadowBias, spotLightShadow.shadowRadius, vSpotLightCoord[ i ] ) : 1.0;
		#endif
		RE_Direct( directLight, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight );
	}
	#pragma unroll_loop_end
#endif
#if ( NUM_DIR_LIGHTS > 0 ) && defined( RE_Direct )
	DirectionalLight directionalLight;
	#if defined( USE_SHADOWMAP ) && NUM_DIR_LIGHT_SHADOWS > 0
	DirectionalLightShadow directionalLightShadow;
	#endif
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_DIR_LIGHTS; i ++ ) {
		directionalLight = directionalLights[ i ];
		getDirectionalLightInfo( directionalLight, directLight );
		#if defined( USE_SHADOWMAP ) && ( UNROLLED_LOOP_INDEX < NUM_DIR_LIGHT_SHADOWS )
		directionalLightShadow = directionalLightShadows[ i ];
		directLight.color *= ( directLight.visible && receiveShadow ) ? getShadow( directionalShadowMap[ i ], directionalLightShadow.shadowMapSize, directionalLightShadow.shadowIntensity, directionalLightShadow.shadowBias, directionalLightShadow.shadowRadius, vDirectionalShadowCoord[ i ] ) : 1.0;
		#endif
		RE_Direct( directLight, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight );
	}
	#pragma unroll_loop_end
#endif
#if ( NUM_RECT_AREA_LIGHTS > 0 ) && defined( RE_Direct_RectArea )
	RectAreaLight rectAreaLight;
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_RECT_AREA_LIGHTS; i ++ ) {
		rectAreaLight = rectAreaLights[ i ];
		RE_Direct_RectArea( rectAreaLight, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight );
	}
	#pragma unroll_loop_end
#endif
#if defined( RE_IndirectDiffuse )
	vec3 iblIrradiance = vec3( 0.0 );
	vec3 irradiance = getAmbientLightIrradiance( ambientLightColor );
	#if defined( USE_LIGHT_PROBES )
		irradiance += getLightProbeIrradiance( lightProbe, geometryNormal );
	#endif
	#if ( NUM_HEMI_LIGHTS > 0 )
		#pragma unroll_loop_start
		for ( int i = 0; i < NUM_HEMI_LIGHTS; i ++ ) {
			irradiance += getHemisphereLightIrradiance( hemisphereLights[ i ], geometryNormal );
		}
		#pragma unroll_loop_end
	#endif
#endif
#if defined( RE_IndirectSpecular )
	vec3 radiance = vec3( 0.0 );
	vec3 clearcoatRadiance = vec3( 0.0 );
#endif`;
var mkt = `#if defined( RE_IndirectDiffuse )
	#ifdef USE_LIGHTMAP
		vec4 lightMapTexel = texture2D( lightMap, vLightMapUv );
		vec3 lightMapIrradiance = lightMapTexel.rgb * lightMapIntensity;
		irradiance += lightMapIrradiance;
	#endif
	#if defined( USE_ENVMAP ) && defined( STANDARD ) && defined( ENVMAP_TYPE_CUBE_UV )
		iblIrradiance += getIBLIrradiance( geometryNormal );
	#endif
#endif
#if defined( USE_ENVMAP ) && defined( RE_IndirectSpecular )
	#ifdef USE_ANISOTROPY
		radiance += getIBLAnisotropyRadiance( geometryViewDir, geometryNormal, material.roughness, material.anisotropyB, material.anisotropy );
	#else
		radiance += getIBLRadiance( geometryViewDir, geometryNormal, material.roughness );
	#endif
	#ifdef USE_CLEARCOAT
		clearcoatRadiance += getIBLRadiance( geometryViewDir, geometryClearcoatNormal, material.clearcoatRoughness );
	#endif
#endif`;
var jkt = `#if defined( RE_IndirectDiffuse )
	RE_IndirectDiffuse( irradiance, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight );
#endif
#if defined( RE_IndirectSpecular )
	RE_IndirectSpecular( radiance, iblIrradiance, clearcoatRadiance, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight );
#endif`;
var xkt = `#if defined( USE_LOGARITHMIC_DEPTH_BUFFER )
	gl_FragDepth = vIsPerspective == 0.0 ? gl_FragCoord.z : log2( vFragDepth ) * logDepthBufFC * 0.5;
#endif`;
var Lkt = `#if defined( USE_LOGARITHMIC_DEPTH_BUFFER )
	uniform float logDepthBufFC;
	varying float vFragDepth;
	varying float vIsPerspective;
#endif`;
var Okt = `#ifdef USE_LOGARITHMIC_DEPTH_BUFFER
	varying float vFragDepth;
	varying float vIsPerspective;
#endif`;
var Hkt = `#ifdef USE_LOGARITHMIC_DEPTH_BUFFER
	vFragDepth = 1.0 + gl_Position.w;
	vIsPerspective = float( isPerspectiveMatrix( projectionMatrix ) );
#endif`;
var Nkt = `#ifdef USE_MAP
	vec4 sampledDiffuseColor = texture2D( map, vMapUv );
	#ifdef DECODE_VIDEO_TEXTURE
		sampledDiffuseColor = sRGBTransferEOTF( sampledDiffuseColor );
	#endif
	diffuseColor *= sampledDiffuseColor;
#endif`;
var Mkt = `#ifdef USE_MAP
	uniform sampler2D map;
#endif`;
var ikt = `#if defined( USE_MAP ) || defined( USE_ALPHAMAP )
	#if defined( USE_POINTS_UV )
		vec2 uv = vUv;
	#else
		vec2 uv = ( uvTransform * vec3( gl_PointCoord.x, 1.0 - gl_PointCoord.y, 1 ) ).xy;
	#endif
#endif
#ifdef USE_MAP
	diffuseColor *= texture2D( map, uv );
#endif
#ifdef USE_ALPHAMAP
	diffuseColor.a *= texture2D( alphaMap, uv ).g;
#endif`;
var pkt = `#if defined( USE_POINTS_UV )
	varying vec2 vUv;
#else
	#if defined( USE_MAP ) || defined( USE_ALPHAMAP )
		uniform mat3 uvTransform;
	#endif
#endif
#ifdef USE_MAP
	uniform sampler2D map;
#endif
#ifdef USE_ALPHAMAP
	uniform sampler2D alphaMap;
#endif`;
var Tkt = `float metalnessFactor = metalness;
#ifdef USE_METALNESSMAP
	vec4 texelMetalness = texture2D( metalnessMap, vMetalnessMapUv );
	metalnessFactor *= texelMetalness.b;
#endif`;
var okt = `#ifdef USE_METALNESSMAP
	uniform sampler2D metalnessMap;
#endif`;
var wkt = `#ifdef USE_INSTANCING_MORPH
	float morphTargetInfluences[ MORPHTARGETS_COUNT ];
	float morphTargetBaseInfluence = texelFetch( morphTexture, ivec2( 0, gl_InstanceID ), 0 ).r;
	for ( int i = 0; i < MORPHTARGETS_COUNT; i ++ ) {
		morphTargetInfluences[i] =  texelFetch( morphTexture, ivec2( i + 1, gl_InstanceID ), 0 ).r;
	}
#endif`;
var hkt = `#if defined( USE_MORPHCOLORS )
	vColor *= morphTargetBaseInfluence;
	for ( int i = 0; i < MORPHTARGETS_COUNT; i ++ ) {
		#if defined( USE_COLOR_ALPHA )
			if ( morphTargetInfluences[ i ] != 0.0 ) vColor += getMorph( gl_VertexID, i, 2 ) * morphTargetInfluences[ i ];
		#elif defined( USE_COLOR )
			if ( morphTargetInfluences[ i ] != 0.0 ) vColor += getMorph( gl_VertexID, i, 2 ).rgb * morphTargetInfluences[ i ];
		#endif
	}
#endif`;
var Gkt = `#ifdef USE_MORPHNORMALS
	objectNormal *= morphTargetBaseInfluence;
	for ( int i = 0; i < MORPHTARGETS_COUNT; i ++ ) {
		if ( morphTargetInfluences[ i ] != 0.0 ) objectNormal += getMorph( gl_VertexID, i, 1 ).xyz * morphTargetInfluences[ i ];
	}
#endif`;
var Vkt = `#ifdef USE_MORPHTARGETS
	#ifndef USE_INSTANCING_MORPH
		uniform float morphTargetBaseInfluence;
		uniform float morphTargetInfluences[ MORPHTARGETS_COUNT ];
	#endif
	uniform sampler2DArray morphTargetsTexture;
	uniform ivec2 morphTargetsTextureSize;
	vec4 getMorph( const in int vertexIndex, const in int morphTargetIndex, const in int offset ) {
		int texelIndex = vertexIndex * MORPHTARGETS_TEXTURE_STRIDE + offset;
		int y = texelIndex / morphTargetsTextureSize.x;
		int x = texelIndex - y * morphTargetsTextureSize.x;
		ivec3 morphUV = ivec3( x, y, morphTargetIndex );
		return texelFetch( morphTargetsTexture, morphUV, 0 );
	}
#endif`;
var Ykt = `#ifdef USE_MORPHTARGETS
	transformed *= morphTargetBaseInfluence;
	for ( int i = 0; i < MORPHTARGETS_COUNT; i ++ ) {
		if ( morphTargetInfluences[ i ] != 0.0 ) transformed += getMorph( gl_VertexID, i, 0 ).xyz * morphTargetInfluences[ i ];
	}
#endif`;
var Dkt = `float faceDirection = gl_FrontFacing ? 1.0 : - 1.0;
#ifdef FLAT_SHADED
	vec3 fdx = dFdx( vViewPosition );
	vec3 fdy = dFdy( vViewPosition );
	vec3 normal = normalize( cross( fdx, fdy ) );
#else
	vec3 normal = normalize( vNormal );
	#ifdef DOUBLE_SIDED
		normal *= faceDirection;
	#endif
#endif
#if defined( USE_NORMALMAP_TANGENTSPACE ) || defined( USE_CLEARCOAT_NORMALMAP ) || defined( USE_ANISOTROPY )
	#ifdef USE_TANGENT
		mat3 tbn = mat3( normalize( vTangent ), normalize( vBitangent ), normal );
	#else
		mat3 tbn = getTangentFrame( - vViewPosition, normal,
		#if defined( USE_NORMALMAP )
			vNormalMapUv
		#elif defined( USE_CLEARCOAT_NORMALMAP )
			vClearcoatNormalMapUv
		#else
			vUv
		#endif
		);
	#endif
	#if defined( DOUBLE_SIDED ) && ! defined( FLAT_SHADED )
		tbn[0] *= faceDirection;
		tbn[1] *= faceDirection;
	#endif
#endif
#ifdef USE_CLEARCOAT_NORMALMAP
	#ifdef USE_TANGENT
		mat3 tbn2 = mat3( normalize( vTangent ), normalize( vBitangent ), normal );
	#else
		mat3 tbn2 = getTangentFrame( - vViewPosition, normal, vClearcoatNormalMapUv );
	#endif
	#if defined( DOUBLE_SIDED ) && ! defined( FLAT_SHADED )
		tbn2[0] *= faceDirection;
		tbn2[1] *= faceDirection;
	#endif
#endif
vec3 nonPerturbedNormal = normal;`;
var qkt = `#ifdef USE_NORMALMAP_OBJECTSPACE
	normal = texture2D( normalMap, vNormalMapUv ).xyz * 2.0 - 1.0;
	#ifdef FLIP_SIDED
		normal = - normal;
	#endif
	#ifdef DOUBLE_SIDED
		normal = normal * faceDirection;
	#endif
	normal = normalize( normalMatrix * normal );
#elif defined( USE_NORMALMAP_TANGENTSPACE )
	vec3 mapN = texture2D( normalMap, vNormalMapUv ).xyz * 2.0 - 1.0;
	mapN.xy *= normalScale;
	normal = normalize( tbn * mapN );
#elif defined( USE_BUMPMAP )
	normal = perturbNormalArb( - vViewPosition, normal, dHdxy_fwd(), faceDirection );
#endif`;
var Fkt = `#ifndef FLAT_SHADED
	varying vec3 vNormal;
	#ifdef USE_TANGENT
		varying vec3 vTangent;
		varying vec3 vBitangent;
	#endif
#endif`;
var Rkt = `#ifndef FLAT_SHADED
	varying vec3 vNormal;
	#ifdef USE_TANGENT
		varying vec3 vTangent;
		varying vec3 vBitangent;
	#endif
#endif`;
var kkt = `#ifndef FLAT_SHADED
	vNormal = normalize( transformedNormal );
	#ifdef USE_TANGENT
		vTangent = normalize( transformedTangent );
		vBitangent = normalize( cross( vNormal, vTangent ) * tangent.w );
	#endif
#endif`;
var Jkt = `#ifdef USE_NORMALMAP
	uniform sampler2D normalMap;
	uniform vec2 normalScale;
#endif
#ifdef USE_NORMALMAP_OBJECTSPACE
	uniform mat3 normalMatrix;
#endif
#if ! defined ( USE_TANGENT ) && ( defined ( USE_NORMALMAP_TANGENTSPACE ) || defined ( USE_CLEARCOAT_NORMALMAP ) || defined( USE_ANISOTROPY ) )
	mat3 getTangentFrame( vec3 eye_pos, vec3 surf_norm, vec2 uv ) {
		vec3 q0 = dFdx( eye_pos.xyz );
		vec3 q1 = dFdy( eye_pos.xyz );
		vec2 st0 = dFdx( uv.st );
		vec2 st1 = dFdy( uv.st );
		vec3 N = surf_norm;
		vec3 q1perp = cross( q1, N );
		vec3 q0perp = cross( N, q0 );
		vec3 T = q1perp * st0.x + q0perp * st1.x;
		vec3 B = q1perp * st0.y + q0perp * st1.y;
		float det = max( dot( T, T ), dot( B, B ) );
		float scale = ( det == 0.0 ) ? 0.0 : inversesqrt( det );
		return mat3( T * scale, B * scale, N );
	}
#endif`;
var gkt = `#ifdef USE_CLEARCOAT
	vec3 clearcoatNormal = nonPerturbedNormal;
#endif`;
var Skt = `#ifdef USE_CLEARCOAT_NORMALMAP
	vec3 clearcoatMapN = texture2D( clearcoatNormalMap, vClearcoatNormalMapUv ).xyz * 2.0 - 1.0;
	clearcoatMapN.xy *= clearcoatNormalScale;
	clearcoatNormal = normalize( tbn2 * clearcoatMapN );
#endif`;
var Bkt = `#ifdef USE_CLEARCOATMAP
	uniform sampler2D clearcoatMap;
#endif
#ifdef USE_CLEARCOAT_NORMALMAP
	uniform sampler2D clearcoatNormalMap;
	uniform vec2 clearcoatNormalScale;
#endif
#ifdef USE_CLEARCOAT_ROUGHNESSMAP
	uniform sampler2D clearcoatRoughnessMap;
#endif`;
var Ukt = `#ifdef USE_IRIDESCENCEMAP
	uniform sampler2D iridescenceMap;
#endif
#ifdef USE_IRIDESCENCE_THICKNESSMAP
	uniform sampler2D iridescenceThicknessMap;
#endif`;
var Ikt = `#ifdef OPAQUE
diffuseColor.a = 1.0;
#endif
#ifdef USE_TRANSMISSION
diffuseColor.a *= material.transmissionAlpha;
#endif
gl_FragColor = vec4( outgoingLight, diffuseColor.a );`;
var Kkt = `vec3 packNormalToRGB( const in vec3 normal ) {
	return normalize( normal ) * 0.5 + 0.5;
}
vec3 unpackRGBToNormal( const in vec3 rgb ) {
	return 2.0 * rgb.xyz - 1.0;
}
const float PackUpscale = 256. / 255.;const float UnpackDownscale = 255. / 256.;const float ShiftRight8 = 1. / 256.;
const float Inv255 = 1. / 255.;
const vec4 PackFactors = vec4( 1.0, 256.0, 256.0 * 256.0, 256.0 * 256.0 * 256.0 );
const vec2 UnpackFactors2 = vec2( UnpackDownscale, 1.0 / PackFactors.g );
const vec3 UnpackFactors3 = vec3( UnpackDownscale / PackFactors.rg, 1.0 / PackFactors.b );
const vec4 UnpackFactors4 = vec4( UnpackDownscale / PackFactors.rgb, 1.0 / PackFactors.a );
vec4 packDepthToRGBA( const in float v ) {
	if( v <= 0.0 )
		return vec4( 0., 0., 0., 0. );
	if( v >= 1.0 )
		return vec4( 1., 1., 1., 1. );
	float vuf;
	float af = modf( v * PackFactors.a, vuf );
	float bf = modf( vuf * ShiftRight8, vuf );
	float gf = modf( vuf * ShiftRight8, vuf );
	return vec4( vuf * Inv255, gf * PackUpscale, bf * PackUpscale, af );
}
vec3 packDepthToRGB( const in float v ) {
	if( v <= 0.0 )
		return vec3( 0., 0., 0. );
	if( v >= 1.0 )
		return vec3( 1., 1., 1. );
	float vuf;
	float bf = modf( v * PackFactors.b, vuf );
	float gf = modf( vuf * ShiftRight8, vuf );
	return vec3( vuf * Inv255, gf * PackUpscale, bf );
}
vec2 packDepthToRG( const in float v ) {
	if( v <= 0.0 )
		return vec2( 0., 0. );
	if( v >= 1.0 )
		return vec2( 1., 1. );
	float vuf;
	float gf = modf( v * 256., vuf );
	return vec2( vuf * Inv255, gf );
}
float unpackRGBAToDepth( const in vec4 v ) {
	return dot( v, UnpackFactors4 );
}
float unpackRGBToDepth( const in vec3 v ) {
	return dot( v, UnpackFactors3 );
}
float unpackRGToDepth( const in vec2 v ) {
	return v.r * UnpackFactors2.r + v.g * UnpackFactors2.g;
}
vec4 pack2HalfToRGBA( const in vec2 v ) {
	vec4 r = vec4( v.x, fract( v.x * 255.0 ), v.y, fract( v.y * 255.0 ) );
	return vec4( r.x - r.y / 255.0, r.y, r.z - r.w / 255.0, r.w );
}
vec2 unpackRGBATo2Half( const in vec4 v ) {
	return vec2( v.x + ( v.y / 255.0 ), v.z + ( v.w / 255.0 ) );
}
float viewZToOrthographicDepth( const in float viewZ, const in float near, const in float far ) {
	return ( viewZ + near ) / ( near - far );
}
float orthographicDepthToViewZ( const in float depth, const in float near, const in float far ) {
	return depth * ( near - far ) - near;
}
float viewZToPerspectiveDepth( const in float viewZ, const in float near, const in float far ) {
	return ( ( near + viewZ ) * far ) / ( ( far - near ) * viewZ );
}
float perspectiveDepthToViewZ( const in float depth, const in float near, const in float far ) {
	return ( near * far ) / ( ( far - near ) * depth - far );
}`;
var Ekt = `#ifdef PREMULTIPLIED_ALPHA
	gl_FragColor.rgb *= gl_FragColor.a;
#endif`;
var Ckt = `vec4 mvPosition = vec4( transformed, 1.0 );
#ifdef USE_BATCHING
	mvPosition = batchingMatrix * mvPosition;
#endif
#ifdef USE_INSTANCING
	mvPosition = instanceMatrix * mvPosition;
#endif
mvPosition = modelViewMatrix * mvPosition;
gl_Position = projectionMatrix * mvPosition;`;
var Akt = `#ifdef DITHERING
	gl_FragColor.rgb = dithering( gl_FragColor.rgb );
#endif`;
var Qkt = `#ifdef DITHERING
	vec3 dithering( vec3 color ) {
		float grid_position = rand( gl_FragCoord.xy );
		vec3 dither_shift_RGB = vec3( 0.25 / 255.0, -0.25 / 255.0, 0.25 / 255.0 );
		dither_shift_RGB = mix( 2.0 * dither_shift_RGB, -2.0 * dither_shift_RGB, grid_position );
		return color + dither_shift_RGB;
	}
#endif`;
var _kt = `float roughnessFactor = roughness;
#ifdef USE_ROUGHNESSMAP
	vec4 texelRoughness = texture2D( roughnessMap, vRoughnessMapUv );
	roughnessFactor *= texelRoughness.g;
#endif`;
var $kt = `#ifdef USE_ROUGHNESSMAP
	uniform sampler2D roughnessMap;
#endif`;
var tJt = `#if NUM_SPOT_LIGHT_COORDS > 0
	varying vec4 vSpotLightCoord[ NUM_SPOT_LIGHT_COORDS ];
#endif
#if NUM_SPOT_LIGHT_MAPS > 0
	uniform sampler2D spotLightMap[ NUM_SPOT_LIGHT_MAPS ];
#endif
#ifdef USE_SHADOWMAP
	#if NUM_DIR_LIGHT_SHADOWS > 0
		uniform sampler2D directionalShadowMap[ NUM_DIR_LIGHT_SHADOWS ];
		varying vec4 vDirectionalShadowCoord[ NUM_DIR_LIGHT_SHADOWS ];
		struct DirectionalLightShadow {
			float shadowIntensity;
			float shadowBias;
			float shadowNormalBias;
			float shadowRadius;
			vec2 shadowMapSize;
		};
		uniform DirectionalLightShadow directionalLightShadows[ NUM_DIR_LIGHT_SHADOWS ];
	#endif
	#if NUM_SPOT_LIGHT_SHADOWS > 0
		uniform sampler2D spotShadowMap[ NUM_SPOT_LIGHT_SHADOWS ];
		struct SpotLightShadow {
			float shadowIntensity;
			float shadowBias;
			float shadowNormalBias;
			float shadowRadius;
			vec2 shadowMapSize;
		};
		uniform SpotLightShadow spotLightShadows[ NUM_SPOT_LIGHT_SHADOWS ];
	#endif
	#if NUM_POINT_LIGHT_SHADOWS > 0
		uniform sampler2D pointShadowMap[ NUM_POINT_LIGHT_SHADOWS ];
		varying vec4 vPointShadowCoord[ NUM_POINT_LIGHT_SHADOWS ];
		struct PointLightShadow {
			float shadowIntensity;
			float shadowBias;
			float shadowNormalBias;
			float shadowRadius;
			vec2 shadowMapSize;
			float shadowCameraNear;
			float shadowCameraFar;
		};
		uniform PointLightShadow pointLightShadows[ NUM_POINT_LIGHT_SHADOWS ];
	#endif
	float texture2DCompare( sampler2D depths, vec2 uv, float compare ) {
		float depth = unpackRGBAToDepth( texture2D( depths, uv ) );
		#ifdef USE_REVERSED_DEPTH_BUFFER
			return step( depth, compare );
		#else
			return step( compare, depth );
		#endif
	}
	vec2 texture2DDistribution( sampler2D shadow, vec2 uv ) {
		return unpackRGBATo2Half( texture2D( shadow, uv ) );
	}
	float VSMShadow( sampler2D shadow, vec2 uv, float compare ) {
		float occlusion = 1.0;
		vec2 distribution = texture2DDistribution( shadow, uv );
		#ifdef USE_REVERSED_DEPTH_BUFFER
			float hard_shadow = step( distribution.x, compare );
		#else
			float hard_shadow = step( compare, distribution.x );
		#endif
		if ( hard_shadow != 1.0 ) {
			float distance = compare - distribution.x;
			float variance = max( 0.00000, distribution.y * distribution.y );
			float softness_probability = variance / (variance + distance * distance );			softness_probability = clamp( ( softness_probability - 0.3 ) / ( 0.95 - 0.3 ), 0.0, 1.0 );			occlusion = clamp( max( hard_shadow, softness_probability ), 0.0, 1.0 );
		}
		return occlusion;
	}
	float getShadow( sampler2D shadowMap, vec2 shadowMapSize, float shadowIntensity, float shadowBias, float shadowRadius, vec4 shadowCoord ) {
		float shadow = 1.0;
		shadowCoord.xyz /= shadowCoord.w;
		shadowCoord.z += shadowBias;
		bool inFrustum = shadowCoord.x >= 0.0 && shadowCoord.x <= 1.0 && shadowCoord.y >= 0.0 && shadowCoord.y <= 1.0;
		bool frustumTest = inFrustum && shadowCoord.z <= 1.0;
		if ( frustumTest ) {
		#if defined( SHADOWMAP_TYPE_PCF )
			vec2 texelSize = vec2( 1.0 ) / shadowMapSize;
			float dx0 = - texelSize.x * shadowRadius;
			float dy0 = - texelSize.y * shadowRadius;
			float dx1 = + texelSize.x * shadowRadius;
			float dy1 = + texelSize.y * shadowRadius;
			float dx2 = dx0 / 2.0;
			float dy2 = dy0 / 2.0;
			float dx3 = dx1 / 2.0;
			float dy3 = dy1 / 2.0;
			shadow = (
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx0, dy0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( 0.0, dy0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx1, dy0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx2, dy2 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( 0.0, dy2 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx3, dy2 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx0, 0.0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx2, 0.0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy, shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx3, 0.0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx1, 0.0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx2, dy3 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( 0.0, dy3 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx3, dy3 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx0, dy1 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( 0.0, dy1 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx1, dy1 ), shadowCoord.z )
			) * ( 1.0 / 17.0 );
		#elif defined( SHADOWMAP_TYPE_PCF_SOFT )
			vec2 texelSize = vec2( 1.0 ) / shadowMapSize;
			float dx = texelSize.x;
			float dy = texelSize.y;
			vec2 uv = shadowCoord.xy;
			vec2 f = fract( uv * shadowMapSize + 0.5 );
			uv -= f * texelSize;
			shadow = (
				texture2DCompare( shadowMap, uv, shadowCoord.z ) +
				texture2DCompare( shadowMap, uv + vec2( dx, 0.0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, uv + vec2( 0.0, dy ), shadowCoord.z ) +
				texture2DCompare( shadowMap, uv + texelSize, shadowCoord.z ) +
				mix( texture2DCompare( shadowMap, uv + vec2( -dx, 0.0 ), shadowCoord.z ),
					 texture2DCompare( shadowMap, uv + vec2( 2.0 * dx, 0.0 ), shadowCoord.z ),
					 f.x ) +
				mix( texture2DCompare( shadowMap, uv + vec2( -dx, dy ), shadowCoord.z ),
					 texture2DCompare( shadowMap, uv + vec2( 2.0 * dx, dy ), shadowCoord.z ),
					 f.x ) +
				mix( texture2DCompare( shadowMap, uv + vec2( 0.0, -dy ), shadowCoord.z ),
					 texture2DCompare( shadowMap, uv + vec2( 0.0, 2.0 * dy ), shadowCoord.z ),
					 f.y ) +
				mix( texture2DCompare( shadowMap, uv + vec2( dx, -dy ), shadowCoord.z ),
					 texture2DCompare( shadowMap, uv + vec2( dx, 2.0 * dy ), shadowCoord.z ),
					 f.y ) +
				mix( mix( texture2DCompare( shadowMap, uv + vec2( -dx, -dy ), shadowCoord.z ),
						  texture2DCompare( shadowMap, uv + vec2( 2.0 * dx, -dy ), shadowCoord.z ),
						  f.x ),
					 mix( texture2DCompare( shadowMap, uv + vec2( -dx, 2.0 * dy ), shadowCoord.z ),
						  texture2DCompare( shadowMap, uv + vec2( 2.0 * dx, 2.0 * dy ), shadowCoord.z ),
						  f.x ),
					 f.y )
			) * ( 1.0 / 9.0 );
		#elif defined( SHADOWMAP_TYPE_VSM )
			shadow = VSMShadow( shadowMap, shadowCoord.xy, shadowCoord.z );
		#else
			shadow = texture2DCompare( shadowMap, shadowCoord.xy, shadowCoord.z );
		#endif
		}
		return mix( 1.0, shadow, shadowIntensity );
	}
	vec2 cubeToUV( vec3 v, float texelSizeY ) {
		vec3 absV = abs( v );
		float scaleToCube = 1.0 / max( absV.x, max( absV.y, absV.z ) );
		absV *= scaleToCube;
		v *= scaleToCube * ( 1.0 - 2.0 * texelSizeY );
		vec2 planar = v.xy;
		float almostATexel = 1.5 * texelSizeY;
		float almostOne = 1.0 - almostATexel;
		if ( absV.z >= almostOne ) {
			if ( v.z > 0.0 )
				planar.x = 4.0 - v.x;
		} else if ( absV.x >= almostOne ) {
			float signX = sign( v.x );
			planar.x = v.z * signX + 2.0 * signX;
		} else if ( absV.y >= almostOne ) {
			float signY = sign( v.y );
			planar.x = v.x + 2.0 * signY + 2.0;
			planar.y = v.z * signY - 2.0;
		}
		return vec2( 0.125, 0.25 ) * planar + vec2( 0.375, 0.75 );
	}
	float getPointShadow( sampler2D shadowMap, vec2 shadowMapSize, float shadowIntensity, float shadowBias, float shadowRadius, vec4 shadowCoord, float shadowCameraNear, float shadowCameraFar ) {
		float shadow = 1.0;
		vec3 lightToPosition = shadowCoord.xyz;

		float lightToPositionLength = length( lightToPosition );
		if ( lightToPositionLength - shadowCameraFar <= 0.0 && lightToPositionLength - shadowCameraNear >= 0.0 ) {
			float dp = ( lightToPositionLength - shadowCameraNear ) / ( shadowCameraFar - shadowCameraNear );			dp += shadowBias;
			vec3 bd3D = normalize( lightToPosition );
			vec2 texelSize = vec2( 1.0 ) / ( shadowMapSize * vec2( 4.0, 2.0 ) );
			#if defined( SHADOWMAP_TYPE_PCF ) || defined( SHADOWMAP_TYPE_PCF_SOFT ) || defined( SHADOWMAP_TYPE_VSM )
				vec2 offset = vec2( - 1, 1 ) * shadowRadius * texelSize.y;
				shadow = (
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.xyy, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.yyy, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.xyx, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.yyx, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.xxy, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.yxy, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.xxx, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.yxx, texelSize.y ), dp )
				) * ( 1.0 / 9.0 );
			#else
				shadow = texture2DCompare( shadowMap, cubeToUV( bd3D, texelSize.y ), dp );
			#endif
		}
		return mix( 1.0, shadow, shadowIntensity );
	}
#endif`;
var eJt = `#if NUM_SPOT_LIGHT_COORDS > 0
	uniform mat4 spotLightMatrix[ NUM_SPOT_LIGHT_COORDS ];
	varying vec4 vSpotLightCoord[ NUM_SPOT_LIGHT_COORDS ];
#endif
#ifdef USE_SHADOWMAP
	#if NUM_DIR_LIGHT_SHADOWS > 0
		uniform mat4 directionalShadowMatrix[ NUM_DIR_LIGHT_SHADOWS ];
		varying vec4 vDirectionalShadowCoord[ NUM_DIR_LIGHT_SHADOWS ];
		struct DirectionalLightShadow {
			float shadowIntensity;
			float shadowBias;
			float shadowNormalBias;
			float shadowRadius;
			vec2 shadowMapSize;
		};
		uniform DirectionalLightShadow directionalLightShadows[ NUM_DIR_LIGHT_SHADOWS ];
	#endif
	#if NUM_SPOT_LIGHT_SHADOWS > 0
		struct SpotLightShadow {
			float shadowIntensity;
			float shadowBias;
			float shadowNormalBias;
			float shadowRadius;
			vec2 shadowMapSize;
		};
		uniform SpotLightShadow spotLightShadows[ NUM_SPOT_LIGHT_SHADOWS ];
	#endif
	#if NUM_POINT_LIGHT_SHADOWS > 0
		uniform mat4 pointShadowMatrix[ NUM_POINT_LIGHT_SHADOWS ];
		varying vec4 vPointShadowCoord[ NUM_POINT_LIGHT_SHADOWS ];
		struct PointLightShadow {
			float shadowIntensity;
			float shadowBias;
			float shadowNormalBias;
			float shadowRadius;
			vec2 shadowMapSize;
			float shadowCameraNear;
			float shadowCameraFar;
		};
		uniform PointLightShadow pointLightShadows[ NUM_POINT_LIGHT_SHADOWS ];
	#endif
#endif`;
var fJt = `#if ( defined( USE_SHADOWMAP ) && ( NUM_DIR_LIGHT_SHADOWS > 0 || NUM_POINT_LIGHT_SHADOWS > 0 ) ) || ( NUM_SPOT_LIGHT_COORDS > 0 )
	vec3 shadowWorldNormal = inverseTransformDirection( transformedNormal, viewMatrix );
	vec4 shadowWorldPosition;
#endif
#if defined( USE_SHADOWMAP )
	#if NUM_DIR_LIGHT_SHADOWS > 0
		#pragma unroll_loop_start
		for ( int i = 0; i < NUM_DIR_LIGHT_SHADOWS; i ++ ) {
			shadowWorldPosition = worldPosition + vec4( shadowWorldNormal * directionalLightShadows[ i ].shadowNormalBias, 0 );
			vDirectionalShadowCoord[ i ] = directionalShadowMatrix[ i ] * shadowWorldPosition;
		}
		#pragma unroll_loop_end
	#endif
	#if NUM_POINT_LIGHT_SHADOWS > 0
		#pragma unroll_loop_start
		for ( int i = 0; i < NUM_POINT_LIGHT_SHADOWS; i ++ ) {
			shadowWorldPosition = worldPosition + vec4( shadowWorldNormal * pointLightShadows[ i ].shadowNormalBias, 0 );
			vPointShadowCoord[ i ] = pointShadowMatrix[ i ] * shadowWorldPosition;
		}
		#pragma unroll_loop_end
	#endif
#endif
#if NUM_SPOT_LIGHT_COORDS > 0
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_SPOT_LIGHT_COORDS; i ++ ) {
		shadowWorldPosition = worldPosition;
		#if ( defined( USE_SHADOWMAP ) && UNROLLED_LOOP_INDEX < NUM_SPOT_LIGHT_SHADOWS )
			shadowWorldPosition.xyz += shadowWorldNormal * spotLightShadows[ i ].shadowNormalBias;
		#endif
		vSpotLightCoord[ i ] = spotLightMatrix[ i ] * shadowWorldPosition;
	}
	#pragma unroll_loop_end
#endif`;
var nJt = `float getShadowMask() {
	float shadow = 1.0;
	#ifdef USE_SHADOWMAP
	#if NUM_DIR_LIGHT_SHADOWS > 0
	DirectionalLightShadow directionalLight;
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_DIR_LIGHT_SHADOWS; i ++ ) {
		directionalLight = directionalLightShadows[ i ];
		shadow *= receiveShadow ? getShadow( directionalShadowMap[ i ], directionalLight.shadowMapSize, directionalLight.shadowIntensity, directionalLight.shadowBias, directionalLight.shadowRadius, vDirectionalShadowCoord[ i ] ) : 1.0;
	}
	#pragma unroll_loop_end
	#endif
	#if NUM_SPOT_LIGHT_SHADOWS > 0
	SpotLightShadow spotLight;
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_SPOT_LIGHT_SHADOWS; i ++ ) {
		spotLight = spotLightShadows[ i ];
		shadow *= receiveShadow ? getShadow( spotShadowMap[ i ], spotLight.shadowMapSize, spotLight.shadowIntensity, spotLight.shadowBias, spotLight.shadowRadius, vSpotLightCoord[ i ] ) : 1.0;
	}
	#pragma unroll_loop_end
	#endif
	#if NUM_POINT_LIGHT_SHADOWS > 0
	PointLightShadow pointLight;
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_POINT_LIGHT_SHADOWS; i ++ ) {
		pointLight = pointLightShadows[ i ];
		shadow *= receiveShadow ? getPointShadow( pointShadowMap[ i ], pointLight.shadowMapSize, pointLight.shadowIntensity, pointLight.shadowBias, pointLight.shadowRadius, vPointShadowCoord[ i ], pointLight.shadowCameraNear, pointLight.shadowCameraFar ) : 1.0;
	}
	#pragma unroll_loop_end
	#endif
	#endif
	return shadow;
}`;
var vJt = `#ifdef USE_SKINNING
	mat4 boneMatX = getBoneMatrix( skinIndex.x );
	mat4 boneMatY = getBoneMatrix( skinIndex.y );
	mat4 boneMatZ = getBoneMatrix( skinIndex.z );
	mat4 boneMatW = getBoneMatrix( skinIndex.w );
#endif`;
var rJt = `#ifdef USE_SKINNING
	uniform mat4 bindMatrix;
	uniform mat4 bindMatrixInverse;
	uniform highp sampler2D boneTexture;
	mat4 getBoneMatrix( const in float i ) {
		int size = textureSize( boneTexture, 0 ).x;
		int j = int( i ) * 4;
		int x = j % size;
		int y = j / size;
		vec4 v1 = texelFetch( boneTexture, ivec2( x, y ), 0 );
		vec4 v2 = texelFetch( boneTexture, ivec2( x + 1, y ), 0 );
		vec4 v3 = texelFetch( boneTexture, ivec2( x + 2, y ), 0 );
		vec4 v4 = texelFetch( boneTexture, ivec2( x + 3, y ), 0 );
		return mat4( v1, v2, v3, v4 );
	}
#endif`;
var zJt = `#ifdef USE_SKINNING
	vec4 skinVertex = bindMatrix * vec4( transformed, 1.0 );
	vec4 skinned = vec4( 0.0 );
	skinned += boneMatX * skinVertex * skinWeight.x;
	skinned += boneMatY * skinVertex * skinWeight.y;
	skinned += boneMatZ * skinVertex * skinWeight.z;
	skinned += boneMatW * skinVertex * skinWeight.w;
	transformed = ( bindMatrixInverse * skinned ).xyz;
#endif`;
var uJt = `#ifdef USE_SKINNING
	mat4 skinMatrix = mat4( 0.0 );
	skinMatrix += skinWeight.x * boneMatX;
	skinMatrix += skinWeight.y * boneMatY;
	skinMatrix += skinWeight.z * boneMatZ;
	skinMatrix += skinWeight.w * boneMatW;
	skinMatrix = bindMatrixInverse * skinMatrix * bindMatrix;
	objectNormal = vec4( skinMatrix * vec4( objectNormal, 0.0 ) ).xyz;
	#ifdef USE_TANGENT
		objectTangent = vec4( skinMatrix * vec4( objectTangent, 0.0 ) ).xyz;
	#endif
#endif`;
var bJt = `float specularStrength;
#ifdef USE_SPECULARMAP
	vec4 texelSpecular = texture2D( specularMap, vSpecularMapUv );
	specularStrength = texelSpecular.r;
#else
	specularStrength = 1.0;
#endif`;
var aJt = `#ifdef USE_SPECULARMAP
	uniform sampler2D specularMap;
#endif`;
var PJt = `#if defined( TONE_MAPPING )
	gl_FragColor.rgb = toneMapping( gl_FragColor.rgb );
#endif`;
var sJt = `#ifndef saturate
#define saturate( a ) clamp( a, 0.0, 1.0 )
#endif
uniform float toneMappingExposure;
vec3 LinearToneMapping( vec3 color ) {
	return saturate( toneMappingExposure * color );
}
vec3 ReinhardToneMapping( vec3 color ) {
	color *= toneMappingExposure;
	return saturate( color / ( vec3( 1.0 ) + color ) );
}
vec3 CineonToneMapping( vec3 color ) {
	color *= toneMappingExposure;
	color = max( vec3( 0.0 ), color - 0.004 );
	return pow( ( color * ( 6.2 * color + 0.5 ) ) / ( color * ( 6.2 * color + 1.7 ) + 0.06 ), vec3( 2.2 ) );
}
vec3 RRTAndODTFit( vec3 v ) {
	vec3 a = v * ( v + 0.0245786 ) - 0.000090537;
	vec3 b = v * ( 0.983729 * v + 0.4329510 ) + 0.238081;
	return a / b;
}
vec3 ACESFilmicToneMapping( vec3 color ) {
	const mat3 ACESInputMat = mat3(
		vec3( 0.59719, 0.07600, 0.02840 ),		vec3( 0.35458, 0.90834, 0.13383 ),
		vec3( 0.04823, 0.01566, 0.83777 )
	);
	const mat3 ACESOutputMat = mat3(
		vec3(  1.60475, -0.10208, -0.00327 ),		vec3( -0.53108,  1.10813, -0.07276 ),
		vec3( -0.07367, -0.00605,  1.07602 )
	);
	color *= toneMappingExposure / 0.6;
	color = ACESInputMat * color;
	color = RRTAndODTFit( color );
	color = ACESOutputMat * color;
	return saturate( color );
}
const mat3 LINEAR_REC2020_TO_LINEAR_SRGB = mat3(
	vec3( 1.6605, - 0.1246, - 0.0182 ),
	vec3( - 0.5876, 1.1329, - 0.1006 ),
	vec3( - 0.0728, - 0.0083, 1.1187 )
);
const mat3 LINEAR_SRGB_TO_LINEAR_REC2020 = mat3(
	vec3( 0.6274, 0.0691, 0.0164 ),
	vec3( 0.3293, 0.9195, 0.0880 ),
	vec3( 0.0433, 0.0113, 0.8956 )
);
vec3 agxDefaultContrastApprox( vec3 x ) {
	vec3 x2 = x * x;
	vec3 x4 = x2 * x2;
	return + 15.5 * x4 * x2
		- 40.14 * x4 * x
		+ 31.96 * x4
		- 6.868 * x2 * x
		+ 0.4298 * x2
		+ 0.1191 * x
		- 0.00232;
}
vec3 AgXToneMapping( vec3 color ) {
	const mat3 AgXInsetMatrix = mat3(
		vec3( 0.856627153315983, 0.137318972929847, 0.11189821299995 ),
		vec3( 0.0951212405381588, 0.761241990602591, 0.0767994186031903 ),
		vec3( 0.0482516061458583, 0.101439036467562, 0.811302368396859 )
	);
	const mat3 AgXOutsetMatrix = mat3(
		vec3( 1.1271005818144368, - 0.1413297634984383, - 0.14132976349843826 ),
		vec3( - 0.11060664309660323, 1.157823702216272, - 0.11060664309660294 ),
		vec3( - 0.016493938717834573, - 0.016493938717834257, 1.2519364065950405 )
	);
	const float AgxMinEv = - 12.47393;	const float AgxMaxEv = 4.026069;
	color *= toneMappingExposure;
	color = LINEAR_SRGB_TO_LINEAR_REC2020 * color;
	color = AgXInsetMatrix * color;
	color = max( color, 1e-10 );	color = log2( color );
	color = ( color - AgxMinEv ) / ( AgxMaxEv - AgxMinEv );
	color = clamp( color, 0.0, 1.0 );
	color = agxDefaultContrastApprox( color );
	color = AgXOutsetMatrix * color;
	color = pow( max( vec3( 0.0 ), color ), vec3( 2.2 ) );
	color = LINEAR_REC2020_TO_LINEAR_SRGB * color;
	color = clamp( color, 0.0, 1.0 );
	return color;
}
vec3 NeutralToneMapping( vec3 color ) {
	const float StartCompression = 0.8 - 0.04;
	const float Desaturation = 0.15;
	color *= toneMappingExposure;
	float x = min( color.r, min( color.g, color.b ) );
	float offset = x < 0.08 ? x - 6.25 * x * x : 0.04;
	color -= offset;
	float peak = max( color.r, max( color.g, color.b ) );
	if ( peak < StartCompression ) return color;
	float d = 1. - StartCompression;
	float newPeak = 1. - d * d / ( peak + d - StartCompression );
	color *= newPeak / peak;
	float g = 1. - 1. / ( Desaturation * ( peak - newPeak ) + 1. );
	return mix( color, vec3( newPeak ), g );
}
vec3 CustomToneMapping( vec3 color ) { return color; }`;
var dJt = `#ifdef USE_TRANSMISSION
	material.transmission = transmission;
	material.transmissionAlpha = 1.0;
	material.thickness = thickness;
	material.attenuationDistance = attenuationDistance;
	material.attenuationColor = attenuationColor;
	#ifdef USE_TRANSMISSIONMAP
		material.transmission *= texture2D( transmissionMap, vTransmissionMapUv ).r;
	#endif
	#ifdef USE_THICKNESSMAP
		material.thickness *= texture2D( thicknessMap, vThicknessMapUv ).g;
	#endif
	vec3 pos = vWorldPosition;
	vec3 v = normalize( cameraPosition - pos );
	vec3 n = inverseTransformDirection( normal, viewMatrix );
	vec4 transmitted = getIBLVolumeRefraction(
		n, v, material.roughness, material.diffuseColor, material.specularColor, material.specularF90,
		pos, modelMatrix, viewMatrix, projectionMatrix, material.dispersion, material.ior, material.thickness,
		material.attenuationColor, material.attenuationDistance );
	material.transmissionAlpha = mix( material.transmissionAlpha, transmitted.a, material.transmission );
	totalDiffuse = mix( totalDiffuse, transmitted.rgb, material.transmission );
#endif`;
var cJt = `#ifdef USE_TRANSMISSION
	uniform float transmission;
	uniform float thickness;
	uniform float attenuationDistance;
	uniform vec3 attenuationColor;
	#ifdef USE_TRANSMISSIONMAP
		uniform sampler2D transmissionMap;
	#endif
	#ifdef USE_THICKNESSMAP
		uniform sampler2D thicknessMap;
	#endif
	uniform vec2 transmissionSamplerSize;
	uniform sampler2D transmissionSamplerMap;
	uniform mat4 modelMatrix;
	uniform mat4 projectionMatrix;
	varying vec3 vWorldPosition;
	float w0( float a ) {
		return ( 1.0 / 6.0 ) * ( a * ( a * ( - a + 3.0 ) - 3.0 ) + 1.0 );
	}
	float w1( float a ) {
		return ( 1.0 / 6.0 ) * ( a *  a * ( 3.0 * a - 6.0 ) + 4.0 );
	}
	float w2( float a ){
		return ( 1.0 / 6.0 ) * ( a * ( a * ( - 3.0 * a + 3.0 ) + 3.0 ) + 1.0 );
	}
	float w3( float a ) {
		return ( 1.0 / 6.0 ) * ( a * a * a );
	}
	float g0( float a ) {
		return w0( a ) + w1( a );
	}
	float g1( float a ) {
		return w2( a ) + w3( a );
	}
	float h0( float a ) {
		return - 1.0 + w1( a ) / ( w0( a ) + w1( a ) );
	}
	float h1( float a ) {
		return 1.0 + w3( a ) / ( w2( a ) + w3( a ) );
	}
	vec4 bicubic( sampler2D tex, vec2 uv, vec4 texelSize, float lod ) {
		uv = uv * texelSize.zw + 0.5;
		vec2 iuv = floor( uv );
		vec2 fuv = fract( uv );
		float g0x = g0( fuv.x );
		float g1x = g1( fuv.x );
		float h0x = h0( fuv.x );
		float h1x = h1( fuv.x );
		float h0y = h0( fuv.y );
		float h1y = h1( fuv.y );
		vec2 p0 = ( vec2( iuv.x + h0x, iuv.y + h0y ) - 0.5 ) * texelSize.xy;
		vec2 p1 = ( vec2( iuv.x + h1x, iuv.y + h0y ) - 0.5 ) * texelSize.xy;
		vec2 p2 = ( vec2( iuv.x + h0x, iuv.y + h1y ) - 0.5 ) * texelSize.xy;
		vec2 p3 = ( vec2( iuv.x + h1x, iuv.y + h1y ) - 0.5 ) * texelSize.xy;
		return g0( fuv.y ) * ( g0x * textureLod( tex, p0, lod ) + g1x * textureLod( tex, p1, lod ) ) +
			g1( fuv.y ) * ( g0x * textureLod( tex, p2, lod ) + g1x * textureLod( tex, p3, lod ) );
	}
	vec4 textureBicubic( sampler2D sampler, vec2 uv, float lod ) {
		vec2 fLodSize = vec2( textureSize( sampler, int( lod ) ) );
		vec2 cLodSize = vec2( textureSize( sampler, int( lod + 1.0 ) ) );
		vec2 fLodSizeInv = 1.0 / fLodSize;
		vec2 cLodSizeInv = 1.0 / cLodSize;
		vec4 fSample = bicubic( sampler, uv, vec4( fLodSizeInv, fLodSize ), floor( lod ) );
		vec4 cSample = bicubic( sampler, uv, vec4( cLodSizeInv, cLodSize ), ceil( lod ) );
		return mix( fSample, cSample, fract( lod ) );
	}
	vec3 getVolumeTransmissionRay( const in vec3 n, const in vec3 v, const in float thickness, const in float ior, const in mat4 modelMatrix ) {
		vec3 refractionVector = refract( - v, normalize( n ), 1.0 / ior );
		vec3 modelScale;
		modelScale.x = length( vec3( modelMatrix[ 0 ].xyz ) );
		modelScale.y = length( vec3( modelMatrix[ 1 ].xyz ) );
		modelScale.z = length( vec3( modelMatrix[ 2 ].xyz ) );
		return normalize( refractionVector ) * thickness * modelScale;
	}
	float applyIorToRoughness( const in float roughness, const in float ior ) {
		return roughness * clamp( ior * 2.0 - 2.0, 0.0, 1.0 );
	}
	vec4 getTransmissionSample( const in vec2 fragCoord, const in float roughness, const in float ior ) {
		float lod = log2( transmissionSamplerSize.x ) * applyIorToRoughness( roughness, ior );
		return textureBicubic( transmissionSamplerMap, fragCoord.xy, lod );
	}
	vec3 volumeAttenuation( const in float transmissionDistance, const in vec3 attenuationColor, const in float attenuationDistance ) {
		if ( isinf( attenuationDistance ) ) {
			return vec3( 1.0 );
		} else {
			vec3 attenuationCoefficient = -log( attenuationColor ) / attenuationDistance;
			vec3 transmittance = exp( - attenuationCoefficient * transmissionDistance );			return transmittance;
		}
	}
	vec4 getIBLVolumeRefraction( const in vec3 n, const in vec3 v, const in float roughness, const in vec3 diffuseColor,
		const in vec3 specularColor, const in float specularF90, const in vec3 position, const in mat4 modelMatrix,
		const in mat4 viewMatrix, const in mat4 projMatrix, const in float dispersion, const in float ior, const in float thickness,
		const in vec3 attenuationColor, const in float attenuationDistance ) {
		vec4 transmittedLight;
		vec3 transmittance;
		#ifdef USE_DISPERSION
			float halfSpread = ( ior - 1.0 ) * 0.025 * dispersion;
			vec3 iors = vec3( ior - halfSpread, ior, ior + halfSpread );
			for ( int i = 0; i < 3; i ++ ) {
				vec3 transmissionRay = getVolumeTransmissionRay( n, v, thickness, iors[ i ], modelMatrix );
				vec3 refractedRayExit = position + transmissionRay;
				vec4 ndcPos = projMatrix * viewMatrix * vec4( refractedRayExit, 1.0 );
				vec2 refractionCoords = ndcPos.xy / ndcPos.w;
				refractionCoords += 1.0;
				refractionCoords /= 2.0;
				vec4 transmissionSample = getTransmissionSample( refractionCoords, roughness, iors[ i ] );
				transmittedLight[ i ] = transmissionSample[ i ];
				transmittedLight.a += transmissionSample.a;
				transmittance[ i ] = diffuseColor[ i ] * volumeAttenuation( length( transmissionRay ), attenuationColor, attenuationDistance )[ i ];
			}
			transmittedLight.a /= 3.0;
		#else
			vec3 transmissionRay = getVolumeTransmissionRay( n, v, thickness, ior, modelMatrix );
			vec3 refractedRayExit = position + transmissionRay;
			vec4 ndcPos = projMatrix * viewMatrix * vec4( refractedRayExit, 1.0 );
			vec2 refractionCoords = ndcPos.xy / ndcPos.w;
			refractionCoords += 1.0;
			refractionCoords /= 2.0;
			transmittedLight = getTransmissionSample( refractionCoords, roughness, ior );
			transmittance = diffuseColor * volumeAttenuation( length( transmissionRay ), attenuationColor, attenuationDistance );
		#endif
		vec3 attenuatedColor = transmittance * transmittedLight.rgb;
		vec3 F = EnvironmentBRDF( n, v, specularColor, specularF90, roughness );
		float transmittanceFactor = ( transmittance.r + transmittance.g + transmittance.b ) / 3.0;
		return vec4( ( 1.0 - F ) * attenuatedColor, 1.0 - ( 1.0 - transmittedLight.a ) * transmittanceFactor );
	}
#endif`;
var lJt = `#if defined( USE_UV ) || defined( USE_ANISOTROPY )
	varying vec2 vUv;
#endif
#ifdef USE_MAP
	varying vec2 vMapUv;
#endif
#ifdef USE_ALPHAMAP
	varying vec2 vAlphaMapUv;
#endif
#ifdef USE_LIGHTMAP
	varying vec2 vLightMapUv;
#endif
#ifdef USE_AOMAP
	varying vec2 vAoMapUv;
#endif
#ifdef USE_BUMPMAP
	varying vec2 vBumpMapUv;
#endif
#ifdef USE_NORMALMAP
	varying vec2 vNormalMapUv;
#endif
#ifdef USE_EMISSIVEMAP
	varying vec2 vEmissiveMapUv;
#endif
#ifdef USE_METALNESSMAP
	varying vec2 vMetalnessMapUv;
#endif
#ifdef USE_ROUGHNESSMAP
	varying vec2 vRoughnessMapUv;
#endif
#ifdef USE_ANISOTROPYMAP
	varying vec2 vAnisotropyMapUv;
#endif
#ifdef USE_CLEARCOATMAP
	varying vec2 vClearcoatMapUv;
#endif
#ifdef USE_CLEARCOAT_NORMALMAP
	varying vec2 vClearcoatNormalMapUv;
#endif
#ifdef USE_CLEARCOAT_ROUGHNESSMAP
	varying vec2 vClearcoatRoughnessMapUv;
#endif
#ifdef USE_IRIDESCENCEMAP
	varying vec2 vIridescenceMapUv;
#endif
#ifdef USE_IRIDESCENCE_THICKNESSMAP
	varying vec2 vIridescenceThicknessMapUv;
#endif
#ifdef USE_SHEEN_COLORMAP
	varying vec2 vSheenColorMapUv;
#endif
#ifdef USE_SHEEN_ROUGHNESSMAP
	varying vec2 vSheenRoughnessMapUv;
#endif
#ifdef USE_SPECULARMAP
	varying vec2 vSpecularMapUv;
#endif
#ifdef USE_SPECULAR_COLORMAP
	varying vec2 vSpecularColorMapUv;
#endif
#ifdef USE_SPECULAR_INTENSITYMAP
	varying vec2 vSpecularIntensityMapUv;
#endif
#ifdef USE_TRANSMISSIONMAP
	uniform mat3 transmissionMapTransform;
	varying vec2 vTransmissionMapUv;
#endif
#ifdef USE_THICKNESSMAP
	uniform mat3 thicknessMapTransform;
	varying vec2 vThicknessMapUv;
#endif`;
var XJt = `#if defined( USE_UV ) || defined( USE_ANISOTROPY )
	varying vec2 vUv;
#endif
#ifdef USE_MAP
	uniform mat3 mapTransform;
	varying vec2 vMapUv;
#endif
#ifdef USE_ALPHAMAP
	uniform mat3 alphaMapTransform;
	varying vec2 vAlphaMapUv;
#endif
#ifdef USE_LIGHTMAP
	uniform mat3 lightMapTransform;
	varying vec2 vLightMapUv;
#endif
#ifdef USE_AOMAP
	uniform mat3 aoMapTransform;
	varying vec2 vAoMapUv;
#endif
#ifdef USE_BUMPMAP
	uniform mat3 bumpMapTransform;
	varying vec2 vBumpMapUv;
#endif
#ifdef USE_NORMALMAP
	uniform mat3 normalMapTransform;
	varying vec2 vNormalMapUv;
#endif
#ifdef USE_DISPLACEMENTMAP
	uniform mat3 displacementMapTransform;
	varying vec2 vDisplacementMapUv;
#endif
#ifdef USE_EMISSIVEMAP
	uniform mat3 emissiveMapTransform;
	varying vec2 vEmissiveMapUv;
#endif
#ifdef USE_METALNESSMAP
	uniform mat3 metalnessMapTransform;
	varying vec2 vMetalnessMapUv;
#endif
#ifdef USE_ROUGHNESSMAP
	uniform mat3 roughnessMapTransform;
	varying vec2 vRoughnessMapUv;
#endif
#ifdef USE_ANISOTROPYMAP
	uniform mat3 anisotropyMapTransform;
	varying vec2 vAnisotropyMapUv;
#endif
#ifdef USE_CLEARCOATMAP
	uniform mat3 clearcoatMapTransform;
	varying vec2 vClearcoatMapUv;
#endif
#ifdef USE_CLEARCOAT_NORMALMAP
	uniform mat3 clearcoatNormalMapTransform;
	varying vec2 vClearcoatNormalMapUv;
#endif
#ifdef USE_CLEARCOAT_ROUGHNESSMAP
	uniform mat3 clearcoatRoughnessMapTransform;
	varying vec2 vClearcoatRoughnessMapUv;
#endif
#ifdef USE_SHEEN_COLORMAP
	uniform mat3 sheenColorMapTransform;
	varying vec2 vSheenColorMapUv;
#endif
#ifdef USE_SHEEN_ROUGHNESSMAP
	uniform mat3 sheenRoughnessMapTransform;
	varying vec2 vSheenRoughnessMapUv;
#endif
#ifdef USE_IRIDESCENCEMAP
	uniform mat3 iridescenceMapTransform;
	varying vec2 vIridescenceMapUv;
#endif
#ifdef USE_IRIDESCENCE_THICKNESSMAP
	uniform mat3 iridescenceThicknessMapTransform;
	varying vec2 vIridescenceThicknessMapUv;
#endif
#ifdef USE_SPECULARMAP
	uniform mat3 specularMapTransform;
	varying vec2 vSpecularMapUv;
#endif
#ifdef USE_SPECULAR_COLORMAP
	uniform mat3 specularColorMapTransform;
	varying vec2 vSpecularColorMapUv;
#endif
#ifdef USE_SPECULAR_INTENSITYMAP
	uniform mat3 specularIntensityMapTransform;
	varying vec2 vSpecularIntensityMapUv;
#endif
#ifdef USE_TRANSMISSIONMAP
	uniform mat3 transmissionMapTransform;
	varying vec2 vTransmissionMapUv;
#endif
#ifdef USE_THICKNESSMAP
	uniform mat3 thicknessMapTransform;
	varying vec2 vThicknessMapUv;
#endif`;
var WJt = `#if defined( USE_UV ) || defined( USE_ANISOTROPY )
	vUv = vec3( uv, 1 ).xy;
#endif
#ifdef USE_MAP
	vMapUv = ( mapTransform * vec3( MAP_UV, 1 ) ).xy;
#endif
#ifdef USE_ALPHAMAP
	vAlphaMapUv = ( alphaMapTransform * vec3( ALPHAMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_LIGHTMAP
	vLightMapUv = ( lightMapTransform * vec3( LIGHTMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_AOMAP
	vAoMapUv = ( aoMapTransform * vec3( AOMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_BUMPMAP
	vBumpMapUv = ( bumpMapTransform * vec3( BUMPMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_NORMALMAP
	vNormalMapUv = ( normalMapTransform * vec3( NORMALMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_DISPLACEMENTMAP
	vDisplacementMapUv = ( displacementMapTransform * vec3( DISPLACEMENTMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_EMISSIVEMAP
	vEmissiveMapUv = ( emissiveMapTransform * vec3( EMISSIVEMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_METALNESSMAP
	vMetalnessMapUv = ( metalnessMapTransform * vec3( METALNESSMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_ROUGHNESSMAP
	vRoughnessMapUv = ( roughnessMapTransform * vec3( ROUGHNESSMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_ANISOTROPYMAP
	vAnisotropyMapUv = ( anisotropyMapTransform * vec3( ANISOTROPYMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_CLEARCOATMAP
	vClearcoatMapUv = ( clearcoatMapTransform * vec3( CLEARCOATMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_CLEARCOAT_NORMALMAP
	vClearcoatNormalMapUv = ( clearcoatNormalMapTransform * vec3( CLEARCOAT_NORMALMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_CLEARCOAT_ROUGHNESSMAP
	vClearcoatRoughnessMapUv = ( clearcoatRoughnessMapTransform * vec3( CLEARCOAT_ROUGHNESSMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_IRIDESCENCEMAP
	vIridescenceMapUv = ( iridescenceMapTransform * vec3( IRIDESCENCEMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_IRIDESCENCE_THICKNESSMAP
	vIridescenceThicknessMapUv = ( iridescenceThicknessMapTransform * vec3( IRIDESCENCE_THICKNESSMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_SHEEN_COLORMAP
	vSheenColorMapUv = ( sheenColorMapTransform * vec3( SHEEN_COLORMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_SHEEN_ROUGHNESSMAP
	vSheenRoughnessMapUv = ( sheenRoughnessMapTransform * vec3( SHEEN_ROUGHNESSMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_SPECULARMAP
	vSpecularMapUv = ( specularMapTransform * vec3( SPECULARMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_SPECULAR_COLORMAP
	vSpecularColorMapUv = ( specularColorMapTransform * vec3( SPECULAR_COLORMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_SPECULAR_INTENSITYMAP
	vSpecularIntensityMapUv = ( specularIntensityMapTransform * vec3( SPECULAR_INTENSITYMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_TRANSMISSIONMAP
	vTransmissionMapUv = ( transmissionMapTransform * vec3( TRANSMISSIONMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_THICKNESSMAP
	vThicknessMapUv = ( thicknessMapTransform * vec3( THICKNESSMAP_UV, 1 ) ).xy;
#endif`;
var yJt = `#if defined( USE_ENVMAP ) || defined( DISTANCE ) || defined ( USE_SHADOWMAP ) || defined ( USE_TRANSMISSION ) || NUM_SPOT_LIGHT_COORDS > 0
	vec4 worldPosition = vec4( transformed, 1.0 );
	#ifdef USE_BATCHING
		worldPosition = batchingMatrix * worldPosition;
	#endif
	#ifdef USE_INSTANCING
		worldPosition = instanceMatrix * worldPosition;
	#endif
	worldPosition = modelMatrix * worldPosition;
#endif`;
const of = { alphahash_fragment: mRt, alphahash_pars_fragment: jRt, alphamap_fragment: xRt, alphamap_pars_fragment: LRt, alphatest_fragment: ORt, alphatest_pars_fragment: HRt, aomap_fragment: NRt, aomap_pars_fragment: MRt, batching_pars_vertex: iRt, batching_vertex: pRt, begin_vertex: TRt, beginnormal_vertex: oRt, bsdfs: wRt, iridescence_fragment: hRt, bumpmap_pars_fragment: GRt, clipping_planes_fragment: VRt, clipping_planes_pars_fragment: YRt, clipping_planes_pars_vertex: DRt, clipping_planes_vertex: qRt, color_fragment: FRt, color_pars_fragment: RRt, color_pars_vertex: kRt, color_vertex: JRt, common: gRt, cube_uv_reflection_fragment: SRt, defaultnormal_vertex: BRt, displacementmap_pars_vertex: URt, displacementmap_vertex: IRt, emissivemap_fragment: KRt, emissivemap_pars_fragment: ERt, colorspace_fragment: CRt, colorspace_pars_fragment: ARt, envmap_fragment: QRt, envmap_common_pars_fragment: _Rt, envmap_pars_fragment: $Rt, envmap_pars_vertex: tkt, envmap_physical_pars_fragment: skt, envmap_vertex: ekt, fog_vertex: fkt, fog_pars_vertex: nkt, fog_fragment: vkt, fog_pars_fragment: rkt, gradientmap_pars_fragment: zkt, lightmap_pars_fragment: ukt, lights_lambert_fragment: bkt, lights_lambert_pars_fragment: akt, lights_pars_begin: Pkt, lights_toon_fragment: dkt, lights_toon_pars_fragment: ckt, lights_phong_fragment: lkt, lights_phong_pars_fragment: Xkt, lights_physical_fragment: Wkt, lights_physical_pars_fragment: ykt, lights_fragment_begin: Zkt, lights_fragment_maps: mkt, lights_fragment_end: jkt, logdepthbuf_fragment: xkt, logdepthbuf_pars_fragment: Lkt, logdepthbuf_pars_vertex: Okt, logdepthbuf_vertex: Hkt, map_fragment: Nkt, map_pars_fragment: Mkt, map_particle_fragment: ikt, map_particle_pars_fragment: pkt, metalnessmap_fragment: Tkt, metalnessmap_pars_fragment: okt, morphinstance_vertex: wkt, morphcolor_vertex: hkt, morphnormal_vertex: Gkt, morphtarget_pars_vertex: Vkt, morphtarget_vertex: Ykt, normal_fragment_begin: Dkt, normal_fragment_maps: qkt, normal_pars_fragment: Fkt, normal_pars_vertex: Rkt, normal_vertex: kkt, normalmap_pars_fragment: Jkt, clearcoat_normal_fragment_begin: gkt, clearcoat_normal_fragment_maps: Skt, clearcoat_pars_fragment: Bkt, iridescence_pars_fragment: Ukt, opaque_fragment: Ikt, packing: Kkt, premultiplied_alpha_fragment: Ekt, project_vertex: Ckt, dithering_fragment: Akt, dithering_pars_fragment: Qkt, roughnessmap_fragment: _kt, roughnessmap_pars_fragment: $kt, shadowmap_pars_fragment: tJt, shadowmap_pars_vertex: eJt, shadowmap_vertex: fJt, shadowmask_pars_fragment: nJt, skinbase_vertex: vJt, skinning_pars_vertex: rJt, skinning_vertex: zJt, skinnormal_vertex: uJt, specularmap_fragment: bJt, specularmap_pars_fragment: aJt, tonemapping_fragment: PJt, tonemapping_pars_fragment: sJt, transmission_fragment: dJt, transmission_pars_fragment: cJt, uv_pars_fragment: lJt, uv_pars_vertex: XJt, uv_vertex: WJt, worldpos_vertex: yJt, background_vert: `varying vec2 vUv;
uniform mat3 uvTransform;
void main() {
	vUv = ( uvTransform * vec3( uv, 1 ) ).xy;
	gl_Position = vec4( position.xy, 1.0, 1.0 );
}`, background_frag: `uniform sampler2D t2D;
uniform float backgroundIntensity;
varying vec2 vUv;
void main() {
	vec4 texColor = texture2D( t2D, vUv );
	#ifdef DECODE_VIDEO_TEXTURE
		texColor = vec4( mix( pow( texColor.rgb * 0.9478672986 + vec3( 0.0521327014 ), vec3( 2.4 ) ), texColor.rgb * 0.0773993808, vec3( lessThanEqual( texColor.rgb, vec3( 0.04045 ) ) ) ), texColor.w );
	#endif
	texColor.rgb *= backgroundIntensity;
	gl_FragColor = texColor;
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
}`, backgroundCube_vert: `varying vec3 vWorldDirection;
#include <common>
void main() {
	vWorldDirection = transformDirection( position, modelMatrix );
	#include <begin_vertex>
	#include <project_vertex>
	gl_Position.z = gl_Position.w;
}`, backgroundCube_frag: `#ifdef ENVMAP_TYPE_CUBE
	uniform samplerCube envMap;
#elif defined( ENVMAP_TYPE_CUBE_UV )
	uniform sampler2D envMap;
#endif
uniform float flipEnvMap;
uniform float backgroundBlurriness;
uniform float backgroundIntensity;
uniform mat3 backgroundRotation;
varying vec3 vWorldDirection;
#include <cube_uv_reflection_fragment>
void main() {
	#ifdef ENVMAP_TYPE_CUBE
		vec4 texColor = textureCube( envMap, backgroundRotation * vec3( flipEnvMap * vWorldDirection.x, vWorldDirection.yz ) );
	#elif defined( ENVMAP_TYPE_CUBE_UV )
		vec4 texColor = textureCubeUV( envMap, backgroundRotation * vWorldDirection, backgroundBlurriness );
	#else
		vec4 texColor = vec4( 0.0, 0.0, 0.0, 1.0 );
	#endif
	texColor.rgb *= backgroundIntensity;
	gl_FragColor = texColor;
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
}`, cube_vert: `varying vec3 vWorldDirection;
#include <common>
void main() {
	vWorldDirection = transformDirection( position, modelMatrix );
	#include <begin_vertex>
	#include <project_vertex>
	gl_Position.z = gl_Position.w;
}`, cube_frag: `uniform samplerCube tCube;
uniform float tFlip;
uniform float opacity;
varying vec3 vWorldDirection;
void main() {
	vec4 texColor = textureCube( tCube, vec3( tFlip * vWorldDirection.x, vWorldDirection.yz ) );
	gl_FragColor = texColor;
	gl_FragColor.a *= opacity;
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
}`, depth_vert: `#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <displacementmap_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
varying vec2 vHighPrecisionZW;
void main() {
	#include <uv_vertex>
	#include <batching_vertex>
	#include <skinbase_vertex>
	#include <morphinstance_vertex>
	#ifdef USE_DISPLACEMENTMAP
		#include <beginnormal_vertex>
		#include <morphnormal_vertex>
		#include <skinnormal_vertex>
	#endif
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	vHighPrecisionZW = gl_Position.zw;
}`, depth_frag: `#if DEPTH_PACKING == 3200
	uniform float opacity;
#endif
#include <common>
#include <packing>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
varying vec2 vHighPrecisionZW;
void main() {
	vec4 diffuseColor = vec4( 1.0 );
	#include <clipping_planes_fragment>
	#if DEPTH_PACKING == 3200
		diffuseColor.a = opacity;
	#endif
	#include <map_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	#include <logdepthbuf_fragment>
	#ifdef USE_REVERSED_DEPTH_BUFFER
		float fragCoordZ = vHighPrecisionZW[ 0 ] / vHighPrecisionZW[ 1 ];
	#else
		float fragCoordZ = 0.5 * vHighPrecisionZW[ 0 ] / vHighPrecisionZW[ 1 ] + 0.5;
	#endif
	#if DEPTH_PACKING == 3200
		gl_FragColor = vec4( vec3( 1.0 - fragCoordZ ), opacity );
	#elif DEPTH_PACKING == 3201
		gl_FragColor = packDepthToRGBA( fragCoordZ );
	#elif DEPTH_PACKING == 3202
		gl_FragColor = vec4( packDepthToRGB( fragCoordZ ), 1.0 );
	#elif DEPTH_PACKING == 3203
		gl_FragColor = vec4( packDepthToRG( fragCoordZ ), 0.0, 1.0 );
	#endif
}`, distanceRGBA_vert: `#define DISTANCE
varying vec3 vWorldPosition;
#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <displacementmap_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <batching_vertex>
	#include <skinbase_vertex>
	#include <morphinstance_vertex>
	#ifdef USE_DISPLACEMENTMAP
		#include <beginnormal_vertex>
		#include <morphnormal_vertex>
		#include <skinnormal_vertex>
	#endif
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <worldpos_vertex>
	#include <clipping_planes_vertex>
	vWorldPosition = worldPosition.xyz;
}`, distanceRGBA_frag: `#define DISTANCE
uniform vec3 referencePosition;
uniform float nearDistance;
uniform float farDistance;
varying vec3 vWorldPosition;
#include <common>
#include <packing>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <clipping_planes_pars_fragment>
void main () {
	vec4 diffuseColor = vec4( 1.0 );
	#include <clipping_planes_fragment>
	#include <map_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	float dist = length( vWorldPosition - referencePosition );
	dist = ( dist - nearDistance ) / ( farDistance - nearDistance );
	dist = saturate( dist );
	gl_FragColor = packDepthToRGBA( dist );
}`, equirect_vert: `varying vec3 vWorldDirection;
#include <common>
void main() {
	vWorldDirection = transformDirection( position, modelMatrix );
	#include <begin_vertex>
	#include <project_vertex>
}`, equirect_frag: `uniform sampler2D tEquirect;
varying vec3 vWorldDirection;
#include <common>
void main() {
	vec3 direction = normalize( vWorldDirection );
	vec2 sampleUV = equirectUv( direction );
	gl_FragColor = texture2D( tEquirect, sampleUV );
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
}`, linedashed_vert: `uniform float scale;
attribute float lineDistance;
varying float vLineDistance;
#include <common>
#include <uv_pars_vertex>
#include <color_pars_vertex>
#include <fog_pars_vertex>
#include <morphtarget_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	vLineDistance = scale * lineDistance;
	#include <uv_vertex>
	#include <color_vertex>
	#include <morphinstance_vertex>
	#include <morphcolor_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	#include <fog_vertex>
}`, linedashed_frag: `uniform vec3 diffuse;
uniform float opacity;
uniform float dashSize;
uniform float totalSize;
varying float vLineDistance;
#include <common>
#include <color_pars_fragment>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <fog_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	if ( mod( vLineDistance, totalSize ) > dashSize ) {
		discard;
	}
	vec3 outgoingLight = vec3( 0.0 );
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <color_fragment>
	outgoingLight = diffuseColor.rgb;
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
}`, meshbasic_vert: `#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <envmap_pars_vertex>
#include <color_pars_vertex>
#include <fog_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <color_vertex>
	#include <morphinstance_vertex>
	#include <morphcolor_vertex>
	#include <batching_vertex>
	#if defined ( USE_ENVMAP ) || defined ( USE_SKINNING )
		#include <beginnormal_vertex>
		#include <morphnormal_vertex>
		#include <skinbase_vertex>
		#include <skinnormal_vertex>
		#include <defaultnormal_vertex>
	#endif
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	#include <worldpos_vertex>
	#include <envmap_vertex>
	#include <fog_vertex>
}`, meshbasic_frag: `uniform vec3 diffuse;
uniform float opacity;
#ifndef FLAT_SHADED
	varying vec3 vNormal;
#endif
#include <common>
#include <dithering_pars_fragment>
#include <color_pars_fragment>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <aomap_pars_fragment>
#include <lightmap_pars_fragment>
#include <envmap_common_pars_fragment>
#include <envmap_pars_fragment>
#include <fog_pars_fragment>
#include <specularmap_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <color_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	#include <specularmap_fragment>
	ReflectedLight reflectedLight = ReflectedLight( vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ) );
	#ifdef USE_LIGHTMAP
		vec4 lightMapTexel = texture2D( lightMap, vLightMapUv );
		reflectedLight.indirectDiffuse += lightMapTexel.rgb * lightMapIntensity * RECIPROCAL_PI;
	#else
		reflectedLight.indirectDiffuse += vec3( 1.0 );
	#endif
	#include <aomap_fragment>
	reflectedLight.indirectDiffuse *= diffuseColor.rgb;
	vec3 outgoingLight = reflectedLight.indirectDiffuse;
	#include <envmap_fragment>
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
	#include <dithering_fragment>
}`, meshlambert_vert: `#define LAMBERT
varying vec3 vViewPosition;
#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <displacementmap_pars_vertex>
#include <envmap_pars_vertex>
#include <color_pars_vertex>
#include <fog_pars_vertex>
#include <normal_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <shadowmap_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <color_vertex>
	#include <morphinstance_vertex>
	#include <morphcolor_vertex>
	#include <batching_vertex>
	#include <beginnormal_vertex>
	#include <morphnormal_vertex>
	#include <skinbase_vertex>
	#include <skinnormal_vertex>
	#include <defaultnormal_vertex>
	#include <normal_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	vViewPosition = - mvPosition.xyz;
	#include <worldpos_vertex>
	#include <envmap_vertex>
	#include <shadowmap_vertex>
	#include <fog_vertex>
}`, meshlambert_frag: `#define LAMBERT
uniform vec3 diffuse;
uniform vec3 emissive;
uniform float opacity;
#include <common>
#include <packing>
#include <dithering_pars_fragment>
#include <color_pars_fragment>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <aomap_pars_fragment>
#include <lightmap_pars_fragment>
#include <emissivemap_pars_fragment>
#include <envmap_common_pars_fragment>
#include <envmap_pars_fragment>
#include <fog_pars_fragment>
#include <bsdfs>
#include <lights_pars_begin>
#include <normal_pars_fragment>
#include <lights_lambert_pars_fragment>
#include <shadowmap_pars_fragment>
#include <bumpmap_pars_fragment>
#include <normalmap_pars_fragment>
#include <specularmap_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	ReflectedLight reflectedLight = ReflectedLight( vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ) );
	vec3 totalEmissiveRadiance = emissive;
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <color_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	#include <specularmap_fragment>
	#include <normal_fragment_begin>
	#include <normal_fragment_maps>
	#include <emissivemap_fragment>
	#include <lights_lambert_fragment>
	#include <lights_fragment_begin>
	#include <lights_fragment_maps>
	#include <lights_fragment_end>
	#include <aomap_fragment>
	vec3 outgoingLight = reflectedLight.directDiffuse + reflectedLight.indirectDiffuse + totalEmissiveRadiance;
	#include <envmap_fragment>
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
	#include <dithering_fragment>
}`, meshmatcap_vert: `#define MATCAP
varying vec3 vViewPosition;
#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <color_pars_vertex>
#include <displacementmap_pars_vertex>
#include <fog_pars_vertex>
#include <normal_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <color_vertex>
	#include <morphinstance_vertex>
	#include <morphcolor_vertex>
	#include <batching_vertex>
	#include <beginnormal_vertex>
	#include <morphnormal_vertex>
	#include <skinbase_vertex>
	#include <skinnormal_vertex>
	#include <defaultnormal_vertex>
	#include <normal_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	#include <fog_vertex>
	vViewPosition = - mvPosition.xyz;
}`, meshmatcap_frag: `#define MATCAP
uniform vec3 diffuse;
uniform float opacity;
uniform sampler2D matcap;
varying vec3 vViewPosition;
#include <common>
#include <dithering_pars_fragment>
#include <color_pars_fragment>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <fog_pars_fragment>
#include <normal_pars_fragment>
#include <bumpmap_pars_fragment>
#include <normalmap_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <color_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	#include <normal_fragment_begin>
	#include <normal_fragment_maps>
	vec3 viewDir = normalize( vViewPosition );
	vec3 x = normalize( vec3( viewDir.z, 0.0, - viewDir.x ) );
	vec3 y = cross( viewDir, x );
	vec2 uv = vec2( dot( x, normal ), dot( y, normal ) ) * 0.495 + 0.5;
	#ifdef USE_MATCAP
		vec4 matcapColor = texture2D( matcap, uv );
	#else
		vec4 matcapColor = vec4( vec3( mix( 0.2, 0.8, uv.y ) ), 1.0 );
	#endif
	vec3 outgoingLight = diffuseColor.rgb * matcapColor.rgb;
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
	#include <dithering_fragment>
}`, meshnormal_vert: `#define NORMAL
#if defined( FLAT_SHADED ) || defined( USE_BUMPMAP ) || defined( USE_NORMALMAP_TANGENTSPACE )
	varying vec3 vViewPosition;
#endif
#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <displacementmap_pars_vertex>
#include <normal_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <batching_vertex>
	#include <beginnormal_vertex>
	#include <morphinstance_vertex>
	#include <morphnormal_vertex>
	#include <skinbase_vertex>
	#include <skinnormal_vertex>
	#include <defaultnormal_vertex>
	#include <normal_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
#if defined( FLAT_SHADED ) || defined( USE_BUMPMAP ) || defined( USE_NORMALMAP_TANGENTSPACE )
	vViewPosition = - mvPosition.xyz;
#endif
}`, meshnormal_frag: `#define NORMAL
uniform float opacity;
#if defined( FLAT_SHADED ) || defined( USE_BUMPMAP ) || defined( USE_NORMALMAP_TANGENTSPACE )
	varying vec3 vViewPosition;
#endif
#include <packing>
#include <uv_pars_fragment>
#include <normal_pars_fragment>
#include <bumpmap_pars_fragment>
#include <normalmap_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( 0.0, 0.0, 0.0, opacity );
	#include <clipping_planes_fragment>
	#include <logdepthbuf_fragment>
	#include <normal_fragment_begin>
	#include <normal_fragment_maps>
	gl_FragColor = vec4( packNormalToRGB( normal ), diffuseColor.a );
	#ifdef OPAQUE
		gl_FragColor.a = 1.0;
	#endif
}`, meshphong_vert: `#define PHONG
varying vec3 vViewPosition;
#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <displacementmap_pars_vertex>
#include <envmap_pars_vertex>
#include <color_pars_vertex>
#include <fog_pars_vertex>
#include <normal_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <shadowmap_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <color_vertex>
	#include <morphcolor_vertex>
	#include <batching_vertex>
	#include <beginnormal_vertex>
	#include <morphinstance_vertex>
	#include <morphnormal_vertex>
	#include <skinbase_vertex>
	#include <skinnormal_vertex>
	#include <defaultnormal_vertex>
	#include <normal_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	vViewPosition = - mvPosition.xyz;
	#include <worldpos_vertex>
	#include <envmap_vertex>
	#include <shadowmap_vertex>
	#include <fog_vertex>
}`, meshphong_frag: `#define PHONG
uniform vec3 diffuse;
uniform vec3 emissive;
uniform vec3 specular;
uniform float shininess;
uniform float opacity;
#include <common>
#include <packing>
#include <dithering_pars_fragment>
#include <color_pars_fragment>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <aomap_pars_fragment>
#include <lightmap_pars_fragment>
#include <emissivemap_pars_fragment>
#include <envmap_common_pars_fragment>
#include <envmap_pars_fragment>
#include <fog_pars_fragment>
#include <bsdfs>
#include <lights_pars_begin>
#include <normal_pars_fragment>
#include <lights_phong_pars_fragment>
#include <shadowmap_pars_fragment>
#include <bumpmap_pars_fragment>
#include <normalmap_pars_fragment>
#include <specularmap_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	ReflectedLight reflectedLight = ReflectedLight( vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ) );
	vec3 totalEmissiveRadiance = emissive;
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <color_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	#include <specularmap_fragment>
	#include <normal_fragment_begin>
	#include <normal_fragment_maps>
	#include <emissivemap_fragment>
	#include <lights_phong_fragment>
	#include <lights_fragment_begin>
	#include <lights_fragment_maps>
	#include <lights_fragment_end>
	#include <aomap_fragment>
	vec3 outgoingLight = reflectedLight.directDiffuse + reflectedLight.indirectDiffuse + reflectedLight.directSpecular + reflectedLight.indirectSpecular + totalEmissiveRadiance;
	#include <envmap_fragment>
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
	#include <dithering_fragment>
}`, meshphysical_vert: `#define STANDARD
varying vec3 vViewPosition;
#ifdef USE_TRANSMISSION
	varying vec3 vWorldPosition;
#endif
#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <displacementmap_pars_vertex>
#include <color_pars_vertex>
#include <fog_pars_vertex>
#include <normal_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <shadowmap_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <color_vertex>
	#include <morphinstance_vertex>
	#include <morphcolor_vertex>
	#include <batching_vertex>
	#include <beginnormal_vertex>
	#include <morphnormal_vertex>
	#include <skinbase_vertex>
	#include <skinnormal_vertex>
	#include <defaultnormal_vertex>
	#include <normal_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	vViewPosition = - mvPosition.xyz;
	#include <worldpos_vertex>
	#include <shadowmap_vertex>
	#include <fog_vertex>
#ifdef USE_TRANSMISSION
	vWorldPosition = worldPosition.xyz;
#endif
}`, meshphysical_frag: `#define STANDARD
#ifdef PHYSICAL
	#define IOR
	#define USE_SPECULAR
#endif
uniform vec3 diffuse;
uniform vec3 emissive;
uniform float roughness;
uniform float metalness;
uniform float opacity;
#ifdef IOR
	uniform float ior;
#endif
#ifdef USE_SPECULAR
	uniform float specularIntensity;
	uniform vec3 specularColor;
	#ifdef USE_SPECULAR_COLORMAP
		uniform sampler2D specularColorMap;
	#endif
	#ifdef USE_SPECULAR_INTENSITYMAP
		uniform sampler2D specularIntensityMap;
	#endif
#endif
#ifdef USE_CLEARCOAT
	uniform float clearcoat;
	uniform float clearcoatRoughness;
#endif
#ifdef USE_DISPERSION
	uniform float dispersion;
#endif
#ifdef USE_IRIDESCENCE
	uniform float iridescence;
	uniform float iridescenceIOR;
	uniform float iridescenceThicknessMinimum;
	uniform float iridescenceThicknessMaximum;
#endif
#ifdef USE_SHEEN
	uniform vec3 sheenColor;
	uniform float sheenRoughness;
	#ifdef USE_SHEEN_COLORMAP
		uniform sampler2D sheenColorMap;
	#endif
	#ifdef USE_SHEEN_ROUGHNESSMAP
		uniform sampler2D sheenRoughnessMap;
	#endif
#endif
#ifdef USE_ANISOTROPY
	uniform vec2 anisotropyVector;
	#ifdef USE_ANISOTROPYMAP
		uniform sampler2D anisotropyMap;
	#endif
#endif
varying vec3 vViewPosition;
#include <common>
#include <packing>
#include <dithering_pars_fragment>
#include <color_pars_fragment>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <aomap_pars_fragment>
#include <lightmap_pars_fragment>
#include <emissivemap_pars_fragment>
#include <iridescence_fragment>
#include <cube_uv_reflection_fragment>
#include <envmap_common_pars_fragment>
#include <envmap_physical_pars_fragment>
#include <fog_pars_fragment>
#include <lights_pars_begin>
#include <normal_pars_fragment>
#include <lights_physical_pars_fragment>
#include <transmission_pars_fragment>
#include <shadowmap_pars_fragment>
#include <bumpmap_pars_fragment>
#include <normalmap_pars_fragment>
#include <clearcoat_pars_fragment>
#include <iridescence_pars_fragment>
#include <roughnessmap_pars_fragment>
#include <metalnessmap_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	ReflectedLight reflectedLight = ReflectedLight( vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ) );
	vec3 totalEmissiveRadiance = emissive;
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <color_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	#include <roughnessmap_fragment>
	#include <metalnessmap_fragment>
	#include <normal_fragment_begin>
	#include <normal_fragment_maps>
	#include <clearcoat_normal_fragment_begin>
	#include <clearcoat_normal_fragment_maps>
	#include <emissivemap_fragment>
	#include <lights_physical_fragment>
	#include <lights_fragment_begin>
	#include <lights_fragment_maps>
	#include <lights_fragment_end>
	#include <aomap_fragment>
	vec3 totalDiffuse = reflectedLight.directDiffuse + reflectedLight.indirectDiffuse;
	vec3 totalSpecular = reflectedLight.directSpecular + reflectedLight.indirectSpecular;
	#include <transmission_fragment>
	vec3 outgoingLight = totalDiffuse + totalSpecular + totalEmissiveRadiance;
	#ifdef USE_SHEEN
		float sheenEnergyComp = 1.0 - 0.157 * max3( material.sheenColor );
		outgoingLight = outgoingLight * sheenEnergyComp + sheenSpecularDirect + sheenSpecularIndirect;
	#endif
	#ifdef USE_CLEARCOAT
		float dotNVcc = saturate( dot( geometryClearcoatNormal, geometryViewDir ) );
		vec3 Fcc = F_Schlick( material.clearcoatF0, material.clearcoatF90, dotNVcc );
		outgoingLight = outgoingLight * ( 1.0 - material.clearcoat * Fcc ) + ( clearcoatSpecularDirect + clearcoatSpecularIndirect ) * material.clearcoat;
	#endif
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
	#include <dithering_fragment>
}`, meshtoon_vert: `#define TOON
varying vec3 vViewPosition;
#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <displacementmap_pars_vertex>
#include <color_pars_vertex>
#include <fog_pars_vertex>
#include <normal_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <shadowmap_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <color_vertex>
	#include <morphinstance_vertex>
	#include <morphcolor_vertex>
	#include <batching_vertex>
	#include <beginnormal_vertex>
	#include <morphnormal_vertex>
	#include <skinbase_vertex>
	#include <skinnormal_vertex>
	#include <defaultnormal_vertex>
	#include <normal_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	vViewPosition = - mvPosition.xyz;
	#include <worldpos_vertex>
	#include <shadowmap_vertex>
	#include <fog_vertex>
}`, meshtoon_frag: `#define TOON
uniform vec3 diffuse;
uniform vec3 emissive;
uniform float opacity;
#include <common>
#include <packing>
#include <dithering_pars_fragment>
#include <color_pars_fragment>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <aomap_pars_fragment>
#include <lightmap_pars_fragment>
#include <emissivemap_pars_fragment>
#include <gradientmap_pars_fragment>
#include <fog_pars_fragment>
#include <bsdfs>
#include <lights_pars_begin>
#include <normal_pars_fragment>
#include <lights_toon_pars_fragment>
#include <shadowmap_pars_fragment>
#include <bumpmap_pars_fragment>
#include <normalmap_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	ReflectedLight reflectedLight = ReflectedLight( vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ) );
	vec3 totalEmissiveRadiance = emissive;
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <color_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	#include <normal_fragment_begin>
	#include <normal_fragment_maps>
	#include <emissivemap_fragment>
	#include <lights_toon_fragment>
	#include <lights_fragment_begin>
	#include <lights_fragment_maps>
	#include <lights_fragment_end>
	#include <aomap_fragment>
	vec3 outgoingLight = reflectedLight.directDiffuse + reflectedLight.indirectDiffuse + totalEmissiveRadiance;
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
	#include <dithering_fragment>
}`, points_vert: `uniform float size;
uniform float scale;
#include <common>
#include <color_pars_vertex>
#include <fog_pars_vertex>
#include <morphtarget_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
#ifdef USE_POINTS_UV
	varying vec2 vUv;
	uniform mat3 uvTransform;
#endif
void main() {
	#ifdef USE_POINTS_UV
		vUv = ( uvTransform * vec3( uv, 1 ) ).xy;
	#endif
	#include <color_vertex>
	#include <morphinstance_vertex>
	#include <morphcolor_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <project_vertex>
	gl_PointSize = size;
	#ifdef USE_SIZEATTENUATION
		bool isPerspective = isPerspectiveMatrix( projectionMatrix );
		if ( isPerspective ) gl_PointSize *= ( scale / - mvPosition.z );
	#endif
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	#include <worldpos_vertex>
	#include <fog_vertex>
}`, points_frag: `uniform vec3 diffuse;
uniform float opacity;
#include <common>
#include <color_pars_fragment>
#include <map_particle_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <fog_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	vec3 outgoingLight = vec3( 0.0 );
	#include <logdepthbuf_fragment>
	#include <map_particle_fragment>
	#include <color_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	outgoingLight = diffuseColor.rgb;
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
}`, shadow_vert: `#include <common>
#include <batching_pars_vertex>
#include <fog_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <shadowmap_pars_vertex>
void main() {
	#include <batching_vertex>
	#include <beginnormal_vertex>
	#include <morphinstance_vertex>
	#include <morphnormal_vertex>
	#include <skinbase_vertex>
	#include <skinnormal_vertex>
	#include <defaultnormal_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <worldpos_vertex>
	#include <shadowmap_vertex>
	#include <fog_vertex>
}`, shadow_frag: `uniform vec3 color;
uniform float opacity;
#include <common>
#include <packing>
#include <fog_pars_fragment>
#include <bsdfs>
#include <lights_pars_begin>
#include <logdepthbuf_pars_fragment>
#include <shadowmap_pars_fragment>
#include <shadowmask_pars_fragment>
void main() {
	#include <logdepthbuf_fragment>
	gl_FragColor = vec4( color, opacity * ( 1.0 - getShadowMask() ) );
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
}`, sprite_vert: `uniform float rotation;
uniform vec2 center;
#include <common>
#include <uv_pars_vertex>
#include <fog_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	vec4 mvPosition = modelViewMatrix[ 3 ];
	vec2 scale = vec2( length( modelMatrix[ 0 ].xyz ), length( modelMatrix[ 1 ].xyz ) );
	#ifndef USE_SIZEATTENUATION
		bool isPerspective = isPerspectiveMatrix( projectionMatrix );
		if ( isPerspective ) scale *= - mvPosition.z;
	#endif
	vec2 alignedPosition = ( position.xy - ( center - vec2( 0.5 ) ) ) * scale;
	vec2 rotatedPosition;
	rotatedPosition.x = cos( rotation ) * alignedPosition.x - sin( rotation ) * alignedPosition.y;
	rotatedPosition.y = sin( rotation ) * alignedPosition.x + cos( rotation ) * alignedPosition.y;
	mvPosition.xy += rotatedPosition;
	gl_Position = projectionMatrix * mvPosition;
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	#include <fog_vertex>
}`, sprite_frag: `uniform vec3 diffuse;
uniform float opacity;
#include <common>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <fog_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	vec3 outgoingLight = vec3( 0.0 );
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	outgoingLight = diffuseColor.rgb;
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
}` };
const Ue = { common: { diffuse: { value: new Nf(16777215) }, opacity: { value: 1 }, map: { value: null }, mapTransform: { value: new Tf() }, alphaMap: { value: null }, alphaMapTransform: { value: new Tf() }, alphaTest: { value: 0 } }, specularmap: { specularMap: { value: null }, specularMapTransform: { value: new Tf() } }, envmap: { envMap: { value: null }, envMapRotation: { value: new Tf() }, flipEnvMap: { value: -1 }, reflectivity: { value: 1 }, ior: { value: 1.5 }, refractionRatio: { value: 0.98 } }, aomap: { aoMap: { value: null }, aoMapIntensity: { value: 1 }, aoMapTransform: { value: new Tf() } }, lightmap: { lightMap: { value: null }, lightMapIntensity: { value: 1 }, lightMapTransform: { value: new Tf() } }, bumpmap: { bumpMap: { value: null }, bumpMapTransform: { value: new Tf() }, bumpScale: { value: 1 } }, normalmap: { normalMap: { value: null }, normalMapTransform: { value: new Tf() }, normalScale: { value: new Me(1, 1) } }, displacementmap: { displacementMap: { value: null }, displacementMapTransform: { value: new Tf() }, displacementScale: { value: 1 }, displacementBias: { value: 0 } }, emissivemap: { emissiveMap: { value: null }, emissiveMapTransform: { value: new Tf() } }, metalnessmap: { metalnessMap: { value: null }, metalnessMapTransform: { value: new Tf() } }, roughnessmap: { roughnessMap: { value: null }, roughnessMapTransform: { value: new Tf() } }, gradientmap: { gradientMap: { value: null } }, fog: { fogDensity: { value: 25e-5 }, fogNear: { value: 1 }, fogFar: { value: 2e3 }, fogColor: { value: new Nf(16777215) } }, lights: { ambientLightColor: { value: [] }, lightProbe: { value: [] }, directionalLights: { value: [], properties: { direction: {}, color: {} } }, directionalLightShadows: { value: [], properties: { shadowIntensity: 1, shadowBias: {}, shadowNormalBias: {}, shadowRadius: {}, shadowMapSize: {} } }, directionalShadowMap: { value: [] }, directionalShadowMatrix: { value: [] }, spotLights: { value: [], properties: { color: {}, position: {}, direction: {}, distance: {}, coneCos: {}, penumbraCos: {}, decay: {} } }, spotLightShadows: { value: [], properties: { shadowIntensity: 1, shadowBias: {}, shadowNormalBias: {}, shadowRadius: {}, shadowMapSize: {} } }, spotLightMap: { value: [] }, spotShadowMap: { value: [] }, spotLightMatrix: { value: [] }, pointLights: { value: [], properties: { color: {}, position: {}, decay: {}, distance: {} } }, pointLightShadows: { value: [], properties: { shadowIntensity: 1, shadowBias: {}, shadowNormalBias: {}, shadowRadius: {}, shadowMapSize: {}, shadowCameraNear: {}, shadowCameraFar: {} } }, pointShadowMap: { value: [] }, pointShadowMatrix: { value: [] }, hemisphereLights: { value: [], properties: { direction: {}, skyColor: {}, groundColor: {} } }, rectAreaLights: { value: [], properties: { color: {}, position: {}, width: {}, height: {} } }, ltc_1: { value: null }, ltc_2: { value: null } }, points: { diffuse: { value: new Nf(16777215) }, opacity: { value: 1 }, size: { value: 1 }, scale: { value: 1 }, map: { value: null }, alphaMap: { value: null }, alphaMapTransform: { value: new Tf() }, alphaTest: { value: 0 }, uvTransform: { value: new Tf() } }, sprite: { diffuse: { value: new Nf(16777215) }, opacity: { value: 1 }, center: { value: new Me(0.5, 0.5) }, rotation: { value: 0 }, map: { value: null }, mapTransform: { value: new Tf() }, alphaMap: { value: null }, alphaMapTransform: { value: new Tf() }, alphaTest: { value: 0 } } };
const Hz = { basic: { uniforms: c3([Ue.common, Ue.specularmap, Ue.envmap, Ue.aomap, Ue.lightmap, Ue.fog]), vertexShader: of.meshbasic_vert, fragmentShader: of.meshbasic_frag }, lambert: { uniforms: c3([Ue.common, Ue.specularmap, Ue.envmap, Ue.aomap, Ue.lightmap, Ue.emissivemap, Ue.bumpmap, Ue.normalmap, Ue.displacementmap, Ue.fog, Ue.lights, { emissive: { value: new Nf(0) } }]), vertexShader: of.meshlambert_vert, fragmentShader: of.meshlambert_frag }, phong: { uniforms: c3([Ue.common, Ue.specularmap, Ue.envmap, Ue.aomap, Ue.lightmap, Ue.emissivemap, Ue.bumpmap, Ue.normalmap, Ue.displacementmap, Ue.fog, Ue.lights, { emissive: { value: new Nf(0) }, specular: { value: new Nf(1118481) }, shininess: { value: 30 } }]), vertexShader: of.meshphong_vert, fragmentShader: of.meshphong_frag }, standard: { uniforms: c3([Ue.common, Ue.envmap, Ue.aomap, Ue.lightmap, Ue.emissivemap, Ue.bumpmap, Ue.normalmap, Ue.displacementmap, Ue.roughnessmap, Ue.metalnessmap, Ue.fog, Ue.lights, { emissive: { value: new Nf(0) }, roughness: { value: 1 }, metalness: { value: 0 }, envMapIntensity: { value: 1 } }]), vertexShader: of.meshphysical_vert, fragmentShader: of.meshphysical_frag }, toon: { uniforms: c3([Ue.common, Ue.aomap, Ue.lightmap, Ue.emissivemap, Ue.bumpmap, Ue.normalmap, Ue.displacementmap, Ue.gradientmap, Ue.fog, Ue.lights, { emissive: { value: new Nf(0) } }]), vertexShader: of.meshtoon_vert, fragmentShader: of.meshtoon_frag }, matcap: { uniforms: c3([Ue.common, Ue.bumpmap, Ue.normalmap, Ue.displacementmap, Ue.fog, { matcap: { value: null } }]), vertexShader: of.meshmatcap_vert, fragmentShader: of.meshmatcap_frag }, points: { uniforms: c3([Ue.points, Ue.fog]), vertexShader: of.points_vert, fragmentShader: of.points_frag }, dashed: { uniforms: c3([Ue.common, Ue.fog, { scale: { value: 1 }, dashSize: { value: 1 }, totalSize: { value: 2 } }]), vertexShader: of.linedashed_vert, fragmentShader: of.linedashed_frag }, depth: { uniforms: c3([Ue.common, Ue.displacementmap]), vertexShader: of.depth_vert, fragmentShader: of.depth_frag }, normal: { uniforms: c3([Ue.common, Ue.bumpmap, Ue.normalmap, Ue.displacementmap, { opacity: { value: 1 } }]), vertexShader: of.meshnormal_vert, fragmentShader: of.meshnormal_frag }, sprite: { uniforms: c3([Ue.sprite, Ue.fog]), vertexShader: of.sprite_vert, fragmentShader: of.sprite_frag }, background: { uniforms: { uvTransform: { value: new Tf() }, t2D: { value: null }, backgroundIntensity: { value: 1 } }, vertexShader: of.background_vert, fragmentShader: of.background_frag }, backgroundCube: { uniforms: { envMap: { value: null }, flipEnvMap: { value: -1 }, backgroundBlurriness: { value: 0 }, backgroundIntensity: { value: 1 }, backgroundRotation: { value: new Tf() } }, vertexShader: of.backgroundCube_vert, fragmentShader: of.backgroundCube_frag }, cube: { uniforms: { tCube: { value: null }, tFlip: { value: -1 }, opacity: { value: 1 } }, vertexShader: of.cube_vert, fragmentShader: of.cube_frag }, equirect: { uniforms: { tEquirect: { value: null } }, vertexShader: of.equirect_vert, fragmentShader: of.equirect_frag }, distanceRGBA: { uniforms: c3([Ue.common, Ue.displacementmap, { referencePosition: { value: new ht() }, nearDistance: { value: 1 }, farDistance: { value: 1e3 } }]), vertexShader: of.distanceRGBA_vert, fragmentShader: of.distanceRGBA_frag }, shadow: { uniforms: c3([Ue.lights, Ue.fog, { color: { value: new Nf(0) }, opacity: { value: 1 } }]), vertexShader: of.shadow_vert, fragmentShader: of.shadow_frag } };
Hz.physical = { uniforms: c3([Hz.standard.uniforms, { clearcoat: { value: 0 }, clearcoatMap: { value: null }, clearcoatMapTransform: { value: new Tf() }, clearcoatNormalMap: { value: null }, clearcoatNormalMapTransform: { value: new Tf() }, clearcoatNormalScale: { value: new Me(1, 1) }, clearcoatRoughness: { value: 0 }, clearcoatRoughnessMap: { value: null }, clearcoatRoughnessMapTransform: { value: new Tf() }, dispersion: { value: 0 }, iridescence: { value: 0 }, iridescenceMap: { value: null }, iridescenceMapTransform: { value: new Tf() }, iridescenceIOR: { value: 1.3 }, iridescenceThicknessMinimum: { value: 100 }, iridescenceThicknessMaximum: { value: 400 }, iridescenceThicknessMap: { value: null }, iridescenceThicknessMapTransform: { value: new Tf() }, sheen: { value: 0 }, sheenColor: { value: new Nf(0) }, sheenColorMap: { value: null }, sheenColorMapTransform: { value: new Tf() }, sheenRoughness: { value: 1 }, sheenRoughnessMap: { value: null }, sheenRoughnessMapTransform: { value: new Tf() }, transmission: { value: 0 }, transmissionMap: { value: null }, transmissionMapTransform: { value: new Tf() }, transmissionSamplerSize: { value: new Me() }, transmissionSamplerMap: { value: null }, thickness: { value: 0 }, thicknessMap: { value: null }, thicknessMapTransform: { value: new Tf() }, attenuationDistance: { value: 0 }, attenuationColor: { value: new Nf(0) }, specularColor: { value: new Nf(1, 1, 1) }, specularColorMap: { value: null }, specularColorMapTransform: { value: new Tf() }, specularIntensity: { value: 1 }, specularIntensityMap: { value: null }, specularIntensityMapTransform: { value: new Tf() }, anisotropyVector: { value: new Me() }, anisotropyMap: { value: null }, anisotropyMapTransform: { value: new Tf() } }]), vertexShader: of.meshphysical_vert, fragmentShader: of.meshphysical_frag };
const K6 = { r: 0, b: 0, g: 0 };
const Vb = new xz();
const ZJt = new cn();
function mJt(t, e, f, n, v, r, z) {
  const u = new Nf(0);
  let b = r === true ? 0 : 1, a, P, s = null, d = 0, c = null;
  function l(j) {
    let x = j.isScene === true ? j.background : null;
    return x && x.isTexture && (x = (j.backgroundBlurriness > 0 ? f : e).get(x)), x;
  }
  function W(j) {
    let x = false;
    const L = l(j);
    L === null ? y(u, b) : L && L.isColor && (y(L, 1), x = true);
    const O = t.xr.getEnvironmentBlendMode();
    O === "additive" ? n.buffers.color.setClear(0, 0, 0, 1, z) : O === "alpha-blend" && n.buffers.color.setClear(0, 0, 0, 0, z), (t.autoClear || x) && (n.buffers.depth.setTest(true), n.buffers.depth.setMask(true), n.buffers.color.setMask(true), t.clear(t.autoClearColor, t.autoClearDepth, t.autoClearStencil));
  }
  function X(j, x) {
    const L = l(x);
    L && (L.isCubeTexture || L.mapping === t6) ? (P === void 0 && (P = new Zn(new Lz(1, 1, 1), new Y5({ name: "BackgroundCubeMaterial", uniforms: z1(Hz.backgroundCube.uniforms), vertexShader: Hz.backgroundCube.vertexShader, fragmentShader: Hz.backgroundCube.fragmentShader, side: Y3, depthTest: false, depthWrite: false, fog: false, allowOverride: false })), P.geometry.deleteAttribute("normal"), P.geometry.deleteAttribute("uv"), P.onBeforeRender = function(O, N, i) {
      this.matrixWorld.copyPosition(i.matrixWorld);
    }, Object.defineProperty(P.material, "envMap", { get: function() {
      return this.uniforms.envMap.value;
    } }), v.update(P)), Vb.copy(x.backgroundRotation), Vb.x *= -1, Vb.y *= -1, Vb.z *= -1, L.isCubeTexture && L.isRenderTargetTexture === false && (Vb.y *= -1, Vb.z *= -1), P.material.uniforms.envMap.value = L, P.material.uniforms.flipEnvMap.value = L.isCubeTexture && L.isRenderTargetTexture === false ? -1 : 1, P.material.uniforms.backgroundBlurriness.value = x.backgroundBlurriness, P.material.uniforms.backgroundIntensity.value = x.backgroundIntensity, P.material.uniforms.backgroundRotation.value.setFromMatrix4(ZJt.makeRotationFromEuler(Vb)), P.material.toneMapped = Uf.getTransfer(L.colorSpace) !== dn, (s !== L || d !== L.version || c !== t.toneMapping) && (P.material.needsUpdate = true, s = L, d = L.version, c = t.toneMapping), P.layers.enableAll(), j.unshift(P, P.geometry, P.material, 0, 0, null)) : L && L.isTexture && (a === void 0 && (a = new Zn(new j9(2, 2), new Y5({ name: "BackgroundMaterial", uniforms: z1(Hz.background.uniforms), vertexShader: Hz.background.vertexShader, fragmentShader: Hz.background.fragmentShader, side: M5, depthTest: false, depthWrite: false, fog: false, allowOverride: false })), a.geometry.deleteAttribute("normal"), Object.defineProperty(a.material, "map", { get: function() {
      return this.uniforms.t2D.value;
    } }), v.update(a)), a.material.uniforms.t2D.value = L, a.material.uniforms.backgroundIntensity.value = x.backgroundIntensity, a.material.toneMapped = Uf.getTransfer(L.colorSpace) !== dn, L.matrixAutoUpdate === true && L.updateMatrix(), a.material.uniforms.uvTransform.value.copy(L.matrix), (s !== L || d !== L.version || c !== t.toneMapping) && (a.material.needsUpdate = true, s = L, d = L.version, c = t.toneMapping), a.layers.enableAll(), j.unshift(a, a.geometry, a.material, 0, 0, null));
  }
  function y(j, x) {
    j.getRGB(K6, mK(t)), n.buffers.color.setClear(K6.r, K6.g, K6.b, x, z);
  }
  function m() {
    P !== void 0 && (P.geometry.dispose(), P.material.dispose(), P = void 0), a !== void 0 && (a.geometry.dispose(), a.material.dispose(), a = void 0);
  }
  return { getClearColor: function() {
    return u;
  }, setClearColor: function(j, x = 1) {
    u.set(j), b = x, y(u, b);
  }, getClearAlpha: function() {
    return b;
  }, setClearAlpha: function(j) {
    b = j, y(u, b);
  }, render: W, addToRenderList: X, dispose: m };
}
function jJt(t, e) {
  const f = t.getParameter(t.MAX_VERTEX_ATTRIBS), n = {}, v = d(null);
  let r = v, z = false;
  function u(M, p, T, w, Y) {
    let q = false;
    const D = s(w, T, p);
    r !== D && (r = D, a(r.object)), q = c(M, w, T, Y), q && l(M, w, T, Y), Y !== null && e.update(Y, t.ELEMENT_ARRAY_BUFFER), (q || z) && (z = false, x(M, p, T, w), Y !== null && t.bindBuffer(t.ELEMENT_ARRAY_BUFFER, e.get(Y).buffer));
  }
  function b() {
    return t.createVertexArray();
  }
  function a(M) {
    return t.bindVertexArray(M);
  }
  function P(M) {
    return t.deleteVertexArray(M);
  }
  function s(M, p, T) {
    const w = T.wireframe === true;
    let Y = n[M.id];
    Y === void 0 && (Y = {}, n[M.id] = Y);
    let q = Y[p.id];
    q === void 0 && (q = {}, Y[p.id] = q);
    let D = q[w];
    return D === void 0 && (D = d(b()), q[w] = D), D;
  }
  function d(M) {
    const p = [], T = [], w = [];
    for (let Y = 0; Y < f; Y++) p[Y] = 0, T[Y] = 0, w[Y] = 0;
    return { geometry: null, program: null, wireframe: false, newAttributes: p, enabledAttributes: T, attributeDivisors: w, object: M, attributes: {}, index: null };
  }
  function c(M, p, T, w) {
    const Y = r.attributes, q = p.attributes;
    let D = 0;
    const V = T.getAttributes();
    for (const G in V) if (V[G].location >= 0) {
      const k = Y[G];
      let K = q[G];
      if (K === void 0 && (G === "instanceMatrix" && M.instanceMatrix && (K = M.instanceMatrix), G === "instanceColor" && M.instanceColor && (K = M.instanceColor)), k === void 0 || k.attribute !== K || K && k.data !== K.data) return true;
      D++;
    }
    return r.attributesNum !== D || r.index !== w;
  }
  function l(M, p, T, w) {
    const Y = {}, q = p.attributes;
    let D = 0;
    const V = T.getAttributes();
    for (const G in V) if (V[G].location >= 0) {
      let k = q[G];
      k === void 0 && (G === "instanceMatrix" && M.instanceMatrix && (k = M.instanceMatrix), G === "instanceColor" && M.instanceColor && (k = M.instanceColor));
      const K = {};
      K.attribute = k, k && k.data && (K.data = k.data), Y[G] = K, D++;
    }
    r.attributes = Y, r.attributesNum = D, r.index = w;
  }
  function W() {
    const M = r.newAttributes;
    for (let p = 0, T = M.length; p < T; p++) M[p] = 0;
  }
  function X(M) {
    y(M, 0);
  }
  function y(M, p) {
    const T = r.newAttributes, w = r.enabledAttributes, Y = r.attributeDivisors;
    T[M] = 1, w[M] === 0 && (t.enableVertexAttribArray(M), w[M] = 1), Y[M] !== p && (t.vertexAttribDivisor(M, p), Y[M] = p);
  }
  function m() {
    const M = r.newAttributes, p = r.enabledAttributes;
    for (let T = 0, w = p.length; T < w; T++) p[T] !== M[T] && (t.disableVertexAttribArray(T), p[T] = 0);
  }
  function j(M, p, T, w, Y, q, D) {
    D === true ? t.vertexAttribIPointer(M, p, T, Y, q) : t.vertexAttribPointer(M, p, T, w, Y, q);
  }
  function x(M, p, T, w) {
    W();
    const Y = w.attributes, q = T.getAttributes(), D = p.defaultAttributeValues;
    for (const V in q) {
      const G = q[V];
      if (G.location >= 0) {
        let R = Y[V];
        if (R === void 0 && (V === "instanceMatrix" && M.instanceMatrix && (R = M.instanceMatrix), V === "instanceColor" && M.instanceColor && (R = M.instanceColor)), R !== void 0) {
          const k = R.normalized, K = R.itemSize, _ = e.get(R);
          if (_ === void 0) continue;
          const vt = _.buffer, rt = _.type, A = _.bytesPerElement, Q = rt === t.INT || rt === t.UNSIGNED_INT || R.gpuType === yN;
          if (R.isInterleavedBufferAttribute) {
            const B = R.data, et = B.stride, nt = R.offset;
            if (B.isInstancedInterleavedBuffer) {
              for (let I = 0; I < G.locationSize; I++) y(G.location + I, B.meshPerAttribute);
              M.isInstancedMesh !== true && w._maxInstanceCount === void 0 && (w._maxInstanceCount = B.meshPerAttribute * B.count);
            } else for (let I = 0; I < G.locationSize; I++) X(G.location + I);
            t.bindBuffer(t.ARRAY_BUFFER, vt);
            for (let I = 0; I < G.locationSize; I++) j(G.location + I, K / G.locationSize, rt, k, et * A, (nt + K / G.locationSize * I) * A, Q);
          } else {
            if (R.isInstancedBufferAttribute) {
              for (let B = 0; B < G.locationSize; B++) y(G.location + B, R.meshPerAttribute);
              M.isInstancedMesh !== true && w._maxInstanceCount === void 0 && (w._maxInstanceCount = R.meshPerAttribute * R.count);
            } else for (let B = 0; B < G.locationSize; B++) X(G.location + B);
            t.bindBuffer(t.ARRAY_BUFFER, vt);
            for (let B = 0; B < G.locationSize; B++) j(G.location + B, K / G.locationSize, rt, k, K * A, K / G.locationSize * B * A, Q);
          }
        } else if (D !== void 0) {
          const k = D[V];
          if (k !== void 0) switch (k.length) {
            case 2:
              t.vertexAttrib2fv(G.location, k);
              break;
            case 3:
              t.vertexAttrib3fv(G.location, k);
              break;
            case 4:
              t.vertexAttrib4fv(G.location, k);
              break;
            default:
              t.vertexAttrib1fv(G.location, k);
          }
        }
      }
    }
    m();
  }
  function L() {
    i();
    for (const M in n) {
      const p = n[M];
      for (const T in p) {
        const w = p[T];
        for (const Y in w) P(w[Y].object), delete w[Y];
        delete p[T];
      }
      delete n[M];
    }
  }
  function O(M) {
    if (n[M.id] === void 0) return;
    const p = n[M.id];
    for (const T in p) {
      const w = p[T];
      for (const Y in w) P(w[Y].object), delete w[Y];
      delete p[T];
    }
    delete n[M.id];
  }
  function N(M) {
    for (const p in n) {
      const T = n[p];
      if (T[M.id] === void 0) continue;
      const w = T[M.id];
      for (const Y in w) P(w[Y].object), delete w[Y];
      delete T[M.id];
    }
  }
  function i() {
    H(), z = true, r !== v && (r = v, a(r.object));
  }
  function H() {
    v.geometry = null, v.program = null, v.wireframe = false;
  }
  return { setup: u, reset: i, resetDefaultState: H, dispose: L, releaseStatesOfGeometry: O, releaseStatesOfProgram: N, initAttributes: W, enableAttribute: X, disableUnusedAttributes: m };
}
function xJt(t, e, f) {
  let n;
  function v(a) {
    n = a;
  }
  function r(a, P) {
    t.drawArrays(n, a, P), f.update(P, n, 1);
  }
  function z(a, P, s) {
    s !== 0 && (t.drawArraysInstanced(n, a, P, s), f.update(P, n, s));
  }
  function u(a, P, s) {
    if (s === 0) return;
    e.get("WEBGL_multi_draw").multiDrawArraysWEBGL(n, a, 0, P, 0, s);
    let c = 0;
    for (let l = 0; l < s; l++) c += P[l];
    f.update(c, n, 1);
  }
  function b(a, P, s, d) {
    if (s === 0) return;
    const c = e.get("WEBGL_multi_draw");
    if (c === null) for (let l = 0; l < a.length; l++) z(a[l], P[l], d[l]);
    else {
      c.multiDrawArraysInstancedWEBGL(n, a, 0, P, 0, d, 0, s);
      let l = 0;
      for (let W = 0; W < s; W++) l += P[W] * d[W];
      f.update(l, n, 1);
    }
  }
  this.setMode = v, this.render = r, this.renderInstances = z, this.renderMultiDraw = u, this.renderMultiDrawInstances = b;
}
function LJt(t, e, f, n) {
  let v;
  function r() {
    if (v !== void 0) return v;
    if (e.has("EXT_texture_filter_anisotropic") === true) {
      const N = e.get("EXT_texture_filter_anisotropic");
      v = t.getParameter(N.MAX_TEXTURE_MAX_ANISOTROPY_EXT);
    } else v = 0;
    return v;
  }
  function z(N) {
    return !(N !== W7 && n.convert(N) !== t.getParameter(t.IMPLEMENTATION_COLOR_READ_FORMAT));
  }
  function u(N) {
    const i = N === Ba && (e.has("EXT_color_buffer_half_float") || e.has("EXT_color_buffer_float"));
    return !(N !== yz && n.convert(N) !== t.getParameter(t.IMPLEMENTATION_COLOR_READ_TYPE) && N !== Zz && !i);
  }
  function b(N) {
    if (N === "highp") {
      if (t.getShaderPrecisionFormat(t.VERTEX_SHADER, t.HIGH_FLOAT).precision > 0 && t.getShaderPrecisionFormat(t.FRAGMENT_SHADER, t.HIGH_FLOAT).precision > 0) return "highp";
      N = "mediump";
    }
    return N === "mediump" && t.getShaderPrecisionFormat(t.VERTEX_SHADER, t.MEDIUM_FLOAT).precision > 0 && t.getShaderPrecisionFormat(t.FRAGMENT_SHADER, t.MEDIUM_FLOAT).precision > 0 ? "mediump" : "lowp";
  }
  let a = f.precision !== void 0 ? f.precision : "highp";
  const P = b(a);
  P !== a && (console.warn("THREE.WebGLRenderer:", a, "not supported, using", P, "instead."), a = P);
  const s = f.logarithmicDepthBuffer === true, d = f.reversedDepthBuffer === true && e.has("EXT_clip_control"), c = t.getParameter(t.MAX_TEXTURE_IMAGE_UNITS), l = t.getParameter(t.MAX_VERTEX_TEXTURE_IMAGE_UNITS), W = t.getParameter(t.MAX_TEXTURE_SIZE), X = t.getParameter(t.MAX_CUBE_MAP_TEXTURE_SIZE), y = t.getParameter(t.MAX_VERTEX_ATTRIBS), m = t.getParameter(t.MAX_VERTEX_UNIFORM_VECTORS), j = t.getParameter(t.MAX_VARYING_VECTORS), x = t.getParameter(t.MAX_FRAGMENT_UNIFORM_VECTORS), L = l > 0, O = t.getParameter(t.MAX_SAMPLES);
  return { isWebGL2: true, getMaxAnisotropy: r, getMaxPrecision: b, textureFormatReadable: z, textureTypeReadable: u, precision: a, logarithmicDepthBuffer: s, reversedDepthBuffer: d, maxTextures: c, maxVertexTextures: l, maxTextureSize: W, maxCubemapSize: X, maxAttributes: y, maxVertexUniforms: m, maxVaryings: j, maxFragmentUniforms: x, vertexTextures: L, maxSamples: O };
}
function OJt(t) {
  const e = this;
  let f = null, n = 0, v = false, r = false;
  const z = new q5(), u = new Tf(), b = { value: null, needsUpdate: false };
  this.uniform = b, this.numPlanes = 0, this.numIntersection = 0, this.init = function(s, d) {
    const c = s.length !== 0 || d || n !== 0 || v;
    return v = d, n = s.length, c;
  }, this.beginShadows = function() {
    r = true, P(null);
  }, this.endShadows = function() {
    r = false;
  }, this.setGlobalState = function(s, d) {
    f = P(s, d, 0);
  }, this.setState = function(s, d, c) {
    const l = s.clippingPlanes, W = s.clipIntersection, X = s.clipShadows, y = t.get(s);
    if (!v || l === null || l.length === 0 || r && !X) r ? P(null) : a();
    else {
      const m = r ? 0 : n, j = m * 4;
      let x = y.clippingState || null;
      b.value = x, x = P(l, d, j, c);
      for (let L = 0; L !== j; ++L) x[L] = f[L];
      y.clippingState = x, this.numIntersection = W ? this.numPlanes : 0, this.numPlanes += m;
    }
  };
  function a() {
    b.value !== f && (b.value = f, b.needsUpdate = n > 0), e.numPlanes = n, e.numIntersection = 0;
  }
  function P(s, d, c, l) {
    const W = s !== null ? s.length : 0;
    let X = null;
    if (W !== 0) {
      if (X = b.value, l !== true || X === null) {
        const y = c + W * 4, m = d.matrixWorldInverse;
        u.getNormalMatrix(m), (X === null || X.length < y) && (X = new Float32Array(y));
        for (let j = 0, x = c; j !== W; ++j, x += 4) z.copy(s[j]).applyMatrix4(m, u), z.normal.toArray(X, x), X[x + 3] = z.constant;
      }
      b.value = X, b.needsUpdate = true;
    }
    return e.numPlanes = W, e.numIntersection = 0, X;
  }
}
function HJt(t) {
  let e = /* @__PURE__ */ new WeakMap();
  function f(z, u) {
    return u === $W ? z.mapping = ga : u === lN && (z.mapping = Sa), z;
  }
  function n(z) {
    if (z && z.isTexture) {
      const u = z.mapping;
      if (u === $W || u === lN) if (e.has(z)) {
        const b = e.get(z).texture;
        return f(b, z.mapping);
      } else {
        const b = z.image;
        if (b && b.height > 0) {
          const a = new NK(b.height);
          return a.fromEquirectangularTexture(t, z), e.set(z, a), z.addEventListener("dispose", v), f(a.texture, z.mapping);
        } else return null;
      }
    }
    return z;
  }
  function v(z) {
    const u = z.target;
    u.removeEventListener("dispose", v);
    const b = e.get(u);
    b !== void 0 && (e.delete(u), b.dispose());
  }
  function r() {
    e = /* @__PURE__ */ new WeakMap();
  }
  return { get: n, dispose: r };
}
const X1 = 4;
const bE = [0.125, 0.215, 0.35, 0.446, 0.526, 0.582];
const Yb = 20;
const SM = new fE();
const aE = new Nf();
let BM = null;
let UM = 0;
let IM = 0;
let KM = false;
const Db = (1 + Math.sqrt(5)) / 2;
const W1 = 1 / Db;
const PE = [new ht(-Db, W1, 0), new ht(Db, W1, 0), new ht(-W1, 0, Db), new ht(W1, 0, Db), new ht(0, Db, -W1), new ht(0, Db, W1), new ht(-1, 1, -1), new ht(1, 1, -1), new ht(-1, 1, 1), new ht(1, 1, 1)];
const NJt = new ht();
class EM {
  constructor(e) {
    this._renderer = e, this._pingPongRenderTarget = null, this._lodMax = 0, this._cubeSize = 0, this._lodPlanes = [], this._sizeLods = [], this._sigmas = [], this._blurMaterial = null, this._cubemapMaterial = null, this._equirectMaterial = null, this._compileMaterial(this._blurMaterial);
  }
  fromScene(e, f = 0, n = 0.1, v = 100, r = {}) {
    const { size: z = 256, position: u = NJt } = r;
    BM = this._renderer.getRenderTarget(), UM = this._renderer.getActiveCubeFace(), IM = this._renderer.getActiveMipmapLevel(), KM = this._renderer.xr.enabled, this._renderer.xr.enabled = false, this._setSize(z);
    const b = this._allocateTargets();
    return b.depthBuffer = true, this._sceneToCubeUV(e, n, v, b, u), f > 0 && this._blur(b, 0, 0, f), this._applyPMREM(b), this._cleanup(b), b;
  }
  fromEquirectangular(e, f = null) {
    return this._fromTexture(e, f);
  }
  fromCubemap(e, f = null) {
    return this._fromTexture(e, f);
  }
  compileCubemapShader() {
    this._cubemapMaterial === null && (this._cubemapMaterial = cE(), this._compileMaterial(this._cubemapMaterial));
  }
  compileEquirectangularShader() {
    this._equirectMaterial === null && (this._equirectMaterial = dE(), this._compileMaterial(this._equirectMaterial));
  }
  dispose() {
    this._dispose(), this._cubemapMaterial !== null && this._cubemapMaterial.dispose(), this._equirectMaterial !== null && this._equirectMaterial.dispose();
  }
  _setSize(e) {
    this._lodMax = Math.floor(Math.log2(e)), this._cubeSize = Math.pow(2, this._lodMax);
  }
  _dispose() {
    this._blurMaterial !== null && this._blurMaterial.dispose(), this._pingPongRenderTarget !== null && this._pingPongRenderTarget.dispose();
    for (let e = 0; e < this._lodPlanes.length; e++) this._lodPlanes[e].dispose();
  }
  _cleanup(e) {
    this._renderer.setRenderTarget(BM, UM, IM), this._renderer.xr.enabled = KM, e.scissorTest = false, E6(e, 0, 0, e.width, e.height);
  }
  _fromTexture(e, f) {
    e.mapping === ga || e.mapping === Sa ? this._setSize(e.image.length === 0 ? 16 : e.image[0].width || e.image[0].image.width) : this._setSize(e.image.width / 4), BM = this._renderer.getRenderTarget(), UM = this._renderer.getActiveCubeFace(), IM = this._renderer.getActiveMipmapLevel(), KM = this._renderer.xr.enabled, this._renderer.xr.enabled = false;
    const n = f || this._allocateTargets();
    return this._textureToCubeUV(e, n), this._applyPMREM(n), this._cleanup(n), n;
  }
  _allocateTargets() {
    const e = 3 * Math.max(this._cubeSize, 112), f = 4 * this._cubeSize, n = { magFilter: Wz, minFilter: Wz, generateMipmaps: false, type: Ba, format: W7, colorSpace: Ua, depthBuffer: false }, v = sE(e, f, n);
    if (this._pingPongRenderTarget === null || this._pingPongRenderTarget.width !== e || this._pingPongRenderTarget.height !== f) {
      this._pingPongRenderTarget !== null && this._dispose(), this._pingPongRenderTarget = sE(e, f, n);
      const { _lodMax: r } = this;
      ({ sizeLods: this._sizeLods, lodPlanes: this._lodPlanes, sigmas: this._sigmas } = MJt(r)), this._blurMaterial = iJt(r, e, f);
    }
    return v;
  }
  _compileMaterial(e) {
    const f = new Zn(this._lodPlanes[0], e);
    this._renderer.compile(f, SM);
  }
  _sceneToCubeUV(e, f, n, v, r) {
    const b = new qr(90, 1, f, n), a = [1, -1, 1, 1, 1, 1], P = [1, 1, 1, -1, -1, -1], s = this._renderer, d = s.autoClear, c = s.toneMapping;
    s.getClearColor(aE), s.toneMapping = p5, s.autoClear = false, s.state.buffers.depth.getReversed() && (s.setRenderTarget(v), s.clearDepth(), s.setRenderTarget(null));
    const W = new j6({ name: "PMREM.Background", side: Y3, depthWrite: false, depthTest: false }), X = new Zn(new Lz(), W);
    let y = false;
    const m = e.background;
    m ? m.isColor && (W.color.copy(m), e.background = null, y = true) : (W.color.copy(aE), y = true);
    for (let j = 0; j < 6; j++) {
      const x = j % 3;
      x === 0 ? (b.up.set(0, a[j], 0), b.position.set(r.x, r.y, r.z), b.lookAt(r.x + P[j], r.y, r.z)) : x === 1 ? (b.up.set(0, 0, a[j]), b.position.set(r.x, r.y, r.z), b.lookAt(r.x, r.y + P[j], r.z)) : (b.up.set(0, a[j], 0), b.position.set(r.x, r.y, r.z), b.lookAt(r.x, r.y, r.z + P[j]));
      const L = this._cubeSize;
      E6(v, x * L, j > 2 ? L : 0, L, L), s.setRenderTarget(v), y && s.render(X, b), s.render(e, b);
    }
    X.geometry.dispose(), X.material.dispose(), s.toneMapping = c, s.autoClear = d, e.background = m;
  }
  _textureToCubeUV(e, f) {
    const n = this._renderer, v = e.mapping === ga || e.mapping === Sa;
    v ? (this._cubemapMaterial === null && (this._cubemapMaterial = cE()), this._cubemapMaterial.uniforms.flipEnvMap.value = e.isRenderTargetTexture === false ? -1 : 1) : this._equirectMaterial === null && (this._equirectMaterial = dE());
    const r = v ? this._cubemapMaterial : this._equirectMaterial, z = new Zn(this._lodPlanes[0], r), u = r.uniforms;
    u.envMap.value = e;
    const b = this._cubeSize;
    E6(f, 0, 0, 3 * b, 2 * b), n.setRenderTarget(f), n.render(z, SM);
  }
  _applyPMREM(e) {
    const f = this._renderer, n = f.autoClear;
    f.autoClear = false;
    const v = this._lodPlanes.length;
    for (let r = 1; r < v; r++) {
      const z = Math.sqrt(this._sigmas[r] * this._sigmas[r] - this._sigmas[r - 1] * this._sigmas[r - 1]), u = PE[(v - r - 1) % PE.length];
      this._blur(e, r - 1, r, z, u);
    }
    f.autoClear = n;
  }
  _blur(e, f, n, v, r) {
    const z = this._pingPongRenderTarget;
    this._halfBlur(e, z, f, n, v, "latitudinal", r), this._halfBlur(z, e, n, n, v, "longitudinal", r);
  }
  _halfBlur(e, f, n, v, r, z, u) {
    const b = this._renderer, a = this._blurMaterial;
    z !== "latitudinal" && z !== "longitudinal" && console.error("blur direction must be either latitudinal or longitudinal!");
    const P = 3, s = new Zn(this._lodPlanes[v], a), d = a.uniforms, c = this._sizeLods[n] - 1, l = isFinite(r) ? Math.PI / (2 * c) : 2 * Math.PI / (2 * Yb - 1), W = r / l, X = isFinite(r) ? 1 + Math.floor(P * W) : Yb;
    X > Yb && console.warn(`sigmaRadians, ${r}, is too large and will clip, as it requested ${X} samples when the maximum is set to ${Yb}`);
    const y = [];
    let m = 0;
    for (let N = 0; N < Yb; ++N) {
      const i = N / W, H = Math.exp(-i * i / 2);
      y.push(H), N === 0 ? m += H : N < X && (m += 2 * H);
    }
    for (let N = 0; N < y.length; N++) y[N] = y[N] / m;
    d.envMap.value = e.texture, d.samples.value = X, d.weights.value = y, d.latitudinal.value = z === "latitudinal", u && (d.poleAxis.value = u);
    const { _lodMax: j } = this;
    d.dTheta.value = l, d.mipInt.value = j - n;
    const x = this._sizeLods[v], L = 3 * x * (v > j - X1 ? v - j + X1 : 0), O = 4 * (this._cubeSize - x);
    E6(f, L, O, 3 * x, 2 * x), b.setRenderTarget(f), b.render(s, SM);
  }
}
function MJt(t) {
  const e = [], f = [], n = [];
  let v = t;
  const r = t - X1 + 1 + bE.length;
  for (let z = 0; z < r; z++) {
    const u = Math.pow(2, v);
    f.push(u);
    let b = 1 / u;
    z > t - X1 ? b = bE[z - t + X1 - 1] : z === 0 && (b = 0), n.push(b);
    const a = 1 / (u - 2), P = -a, s = 1 + a, d = [P, P, s, P, s, s, P, P, s, s, P, s], c = 6, l = 6, W = 3, X = 2, y = 1, m = new Float32Array(W * l * c), j = new Float32Array(X * l * c), x = new Float32Array(y * l * c);
    for (let O = 0; O < c; O++) {
      const N = O % 3 * 2 / 3 - 1, i = O > 2 ? 0 : -1, H = [N, i, 0, N + 2 / 3, i, 0, N + 2 / 3, i + 1, 0, N, i, 0, N + 2 / 3, i + 1, 0, N, i + 1, 0];
      m.set(H, W * l * O), j.set(d, X * l * O);
      const M = [O, O, O, O, O, O];
      x.set(M, y * l * O);
    }
    const L = new bv();
    L.setAttribute("position", new ov(m, W)), L.setAttribute("uv", new ov(j, X)), L.setAttribute("faceIndex", new ov(x, y)), e.push(L), v > X1 && v--;
  }
  return { lodPlanes: e, sizeLods: f, sigmas: n };
}
function sE(t, e, f) {
  const n = new ib(t, e, f);
  return n.texture.mapping = t6, n.texture.name = "PMREM.cubeUv", n.scissorTest = true, n;
}
function E6(t, e, f, n, v) {
  t.viewport.set(e, f, n, v), t.scissor.set(e, f, n, v);
}
function iJt(t, e, f) {
  const n = new Float32Array(Yb), v = new ht(0, 1, 0);
  return new Y5({ name: "SphericalGaussianBlur", defines: { n: Yb, CUBEUV_TEXEL_WIDTH: 1 / e, CUBEUV_TEXEL_HEIGHT: 1 / f, CUBEUV_MAX_MIP: `${t}.0` }, uniforms: { envMap: { value: null }, samples: { value: 1 }, weights: { value: n }, latitudinal: { value: false }, dTheta: { value: 0 }, mipInt: { value: 0 }, poleAxis: { value: v } }, vertexShader: CM(), fragmentShader: `

			precision mediump float;
			precision mediump int;

			varying vec3 vOutputDirection;

			uniform sampler2D envMap;
			uniform int samples;
			uniform float weights[ n ];
			uniform bool latitudinal;
			uniform float dTheta;
			uniform float mipInt;
			uniform vec3 poleAxis;

			#define ENVMAP_TYPE_CUBE_UV
			#include <cube_uv_reflection_fragment>

			vec3 getSample( float theta, vec3 axis ) {

				float cosTheta = cos( theta );
				// Rodrigues' axis-angle rotation
				vec3 sampleDirection = vOutputDirection * cosTheta
					+ cross( axis, vOutputDirection ) * sin( theta )
					+ axis * dot( axis, vOutputDirection ) * ( 1.0 - cosTheta );

				return bilinearCubeUV( envMap, sampleDirection, mipInt );

			}

			void main() {

				vec3 axis = latitudinal ? poleAxis : cross( poleAxis, vOutputDirection );

				if ( all( equal( axis, vec3( 0.0 ) ) ) ) {

					axis = vec3( vOutputDirection.z, 0.0, - vOutputDirection.x );

				}

				axis = normalize( axis );

				gl_FragColor = vec4( 0.0, 0.0, 0.0, 1.0 );
				gl_FragColor.rgb += weights[ 0 ] * getSample( 0.0, axis );

				for ( int i = 1; i < n; i++ ) {

					if ( i >= samples ) {

						break;

					}

					float theta = dTheta * float( i );
					gl_FragColor.rgb += weights[ i ] * getSample( -1.0 * theta, axis );
					gl_FragColor.rgb += weights[ i ] * getSample( theta, axis );

				}

			}
		`, blending: i5, depthTest: false, depthWrite: false });
}
function dE() {
  return new Y5({ name: "EquirectangularToCubeUV", uniforms: { envMap: { value: null } }, vertexShader: CM(), fragmentShader: `

			precision mediump float;
			precision mediump int;

			varying vec3 vOutputDirection;

			uniform sampler2D envMap;

			#include <common>

			void main() {

				vec3 outputDirection = normalize( vOutputDirection );
				vec2 uv = equirectUv( outputDirection );

				gl_FragColor = vec4( texture2D ( envMap, uv ).rgb, 1.0 );

			}
		`, blending: i5, depthTest: false, depthWrite: false });
}
function cE() {
  return new Y5({ name: "CubemapToCubeUV", uniforms: { envMap: { value: null }, flipEnvMap: { value: -1 } }, vertexShader: CM(), fragmentShader: `

			precision mediump float;
			precision mediump int;

			uniform float flipEnvMap;

			varying vec3 vOutputDirection;

			uniform samplerCube envMap;

			void main() {

				gl_FragColor = textureCube( envMap, vec3( flipEnvMap * vOutputDirection.x, vOutputDirection.yz ) );

			}
		`, blending: i5, depthTest: false, depthWrite: false });
}
function CM() {
  return `

		precision mediump float;
		precision mediump int;

		attribute float faceIndex;

		varying vec3 vOutputDirection;

		// RH coordinate system; PMREM face-indexing convention
		vec3 getDirection( vec2 uv, float face ) {

			uv = 2.0 * uv - 1.0;

			vec3 direction = vec3( uv, 1.0 );

			if ( face == 0.0 ) {

				direction = direction.zyx; // ( 1, v, u ) pos x

			} else if ( face == 1.0 ) {

				direction = direction.xzy;
				direction.xz *= -1.0; // ( -u, 1, -v ) pos y

			} else if ( face == 2.0 ) {

				direction.x *= -1.0; // ( -u, v, 1 ) pos z

			} else if ( face == 3.0 ) {

				direction = direction.zyx;
				direction.xz *= -1.0; // ( -1, v, -u ) neg x

			} else if ( face == 4.0 ) {

				direction = direction.xzy;
				direction.xy *= -1.0; // ( -u, -1, v ) neg y

			} else if ( face == 5.0 ) {

				direction.z *= -1.0; // ( u, v, -1 ) neg z

			}

			return direction;

		}

		void main() {

			vOutputDirection = getDirection( uv, faceIndex );
			gl_Position = vec4( position, 1.0 );

		}
	`;
}
function pJt(t) {
  let e = /* @__PURE__ */ new WeakMap(), f = null;
  function n(u) {
    if (u && u.isTexture) {
      const b = u.mapping, a = b === $W || b === lN, P = b === ga || b === Sa;
      if (a || P) {
        let s = e.get(u);
        const d = s !== void 0 ? s.texture.pmremVersion : 0;
        if (u.isRenderTargetTexture && u.pmremVersion !== d) return f === null && (f = new EM(t)), s = a ? f.fromEquirectangular(u, s) : f.fromCubemap(u, s), s.texture.pmremVersion = u.pmremVersion, e.set(u, s), s.texture;
        if (s !== void 0) return s.texture;
        {
          const c = u.image;
          return a && c && c.height > 0 || P && c && v(c) ? (f === null && (f = new EM(t)), s = a ? f.fromEquirectangular(u) : f.fromCubemap(u), s.texture.pmremVersion = u.pmremVersion, e.set(u, s), u.addEventListener("dispose", r), s.texture) : null;
        }
      }
    }
    return u;
  }
  function v(u) {
    let b = 0;
    const a = 6;
    for (let P = 0; P < a; P++) u[P] !== void 0 && b++;
    return b === a;
  }
  function r(u) {
    const b = u.target;
    b.removeEventListener("dispose", r);
    const a = e.get(b);
    a !== void 0 && (e.delete(b), a.dispose());
  }
  function z() {
    e = /* @__PURE__ */ new WeakMap(), f !== null && (f.dispose(), f = null);
  }
  return { get: n, dispose: z };
}
function TJt(t) {
  const e = {};
  function f(n) {
    if (e[n] !== void 0) return e[n];
    let v;
    switch (n) {
      case "WEBGL_depth_texture":
        v = t.getExtension("WEBGL_depth_texture") || t.getExtension("MOZ_WEBGL_depth_texture") || t.getExtension("WEBKIT_WEBGL_depth_texture");
        break;
      case "EXT_texture_filter_anisotropic":
        v = t.getExtension("EXT_texture_filter_anisotropic") || t.getExtension("MOZ_EXT_texture_filter_anisotropic") || t.getExtension("WEBKIT_EXT_texture_filter_anisotropic");
        break;
      case "WEBGL_compressed_texture_s3tc":
        v = t.getExtension("WEBGL_compressed_texture_s3tc") || t.getExtension("MOZ_WEBGL_compressed_texture_s3tc") || t.getExtension("WEBKIT_WEBGL_compressed_texture_s3tc");
        break;
      case "WEBGL_compressed_texture_pvrtc":
        v = t.getExtension("WEBGL_compressed_texture_pvrtc") || t.getExtension("WEBKIT_WEBGL_compressed_texture_pvrtc");
        break;
      default:
        v = t.getExtension(n);
    }
    return e[n] = v, v;
  }
  return { has: function(n) {
    return f(n) !== null;
  }, init: function() {
    f("EXT_color_buffer_float"), f("WEBGL_clip_cull_distance"), f("OES_texture_float_linear"), f("EXT_color_buffer_half_float"), f("WEBGL_multisampled_render_to_texture"), f("WEBGL_render_shared_exponent");
  }, get: function(n) {
    const v = f(n);
    return v === null && os("THREE.WebGLRenderer: " + n + " extension not supported."), v;
  } };
}
function oJt(t, e, f, n) {
  const v = {}, r = /* @__PURE__ */ new WeakMap();
  function z(s) {
    const d = s.target;
    d.index !== null && e.remove(d.index);
    for (const l in d.attributes) e.remove(d.attributes[l]);
    d.removeEventListener("dispose", z), delete v[d.id];
    const c = r.get(d);
    c && (e.remove(c), r.delete(d)), n.releaseStatesOfGeometry(d), d.isInstancedBufferGeometry === true && delete d._maxInstanceCount, f.memory.geometries--;
  }
  function u(s, d) {
    return v[d.id] === true || (d.addEventListener("dispose", z), v[d.id] = true, f.memory.geometries++), d;
  }
  function b(s) {
    const d = s.attributes;
    for (const c in d) e.update(d[c], t.ARRAY_BUFFER);
  }
  function a(s) {
    const d = [], c = s.index, l = s.attributes.position;
    let W = 0;
    if (c !== null) {
      const m = c.array;
      W = c.version;
      for (let j = 0, x = m.length; j < x; j += 3) {
        const L = m[j + 0], O = m[j + 1], N = m[j + 2];
        d.push(L, O, O, N, N, L);
      }
    } else if (l !== void 0) {
      const m = l.array;
      W = l.version;
      for (let j = 0, x = m.length / 3 - 1; j < x; j += 3) {
        const L = j + 0, O = j + 1, N = j + 2;
        d.push(L, O, O, N, N, L);
      }
    } else return;
    const X = new (tK(d) ? XK : lK)(d, 1);
    X.version = W;
    const y = r.get(s);
    y && e.remove(y), r.set(s, X);
  }
  function P(s) {
    const d = r.get(s);
    if (d) {
      const c = s.index;
      c !== null && d.version < c.version && a(s);
    } else a(s);
    return r.get(s);
  }
  return { get: u, update: b, getWireframeAttribute: P };
}
function wJt(t, e, f) {
  let n;
  function v(d) {
    n = d;
  }
  let r, z;
  function u(d) {
    r = d.type, z = d.bytesPerElement;
  }
  function b(d, c) {
    t.drawElements(n, c, r, d * z), f.update(c, n, 1);
  }
  function a(d, c, l) {
    l !== 0 && (t.drawElementsInstanced(n, c, r, d * z, l), f.update(c, n, l));
  }
  function P(d, c, l) {
    if (l === 0) return;
    e.get("WEBGL_multi_draw").multiDrawElementsWEBGL(n, c, 0, r, d, 0, l);
    let X = 0;
    for (let y = 0; y < l; y++) X += c[y];
    f.update(X, n, 1);
  }
  function s(d, c, l, W) {
    if (l === 0) return;
    const X = e.get("WEBGL_multi_draw");
    if (X === null) for (let y = 0; y < d.length; y++) a(d[y] / z, c[y], W[y]);
    else {
      X.multiDrawElementsInstancedWEBGL(n, c, 0, r, d, 0, W, 0, l);
      let y = 0;
      for (let m = 0; m < l; m++) y += c[m] * W[m];
      f.update(y, n, 1);
    }
  }
  this.setMode = v, this.setIndex = u, this.render = b, this.renderInstances = a, this.renderMultiDraw = P, this.renderMultiDrawInstances = s;
}
function hJt(t) {
  const e = { geometries: 0, textures: 0 }, f = { frame: 0, calls: 0, triangles: 0, points: 0, lines: 0 };
  function n(r, z, u) {
    switch (f.calls++, z) {
      case t.TRIANGLES:
        f.triangles += u * (r / 3);
        break;
      case t.LINES:
        f.lines += u * (r / 2);
        break;
      case t.LINE_STRIP:
        f.lines += u * (r - 1);
        break;
      case t.LINE_LOOP:
        f.lines += u * r;
        break;
      case t.POINTS:
        f.points += u * r;
        break;
      default:
        console.error("THREE.WebGLInfo: Unknown draw mode:", z);
        break;
    }
  }
  function v() {
    f.calls = 0, f.triangles = 0, f.points = 0, f.lines = 0;
  }
  return { memory: e, render: f, programs: null, autoReset: true, reset: v, update: n };
}
function GJt(t, e, f) {
  const n = /* @__PURE__ */ new WeakMap(), v = new Un();
  function r(z, u, b) {
    const a = z.morphTargetInfluences, P = u.morphAttributes.position || u.morphAttributes.normal || u.morphAttributes.color, s = P !== void 0 ? P.length : 0;
    let d = n.get(u);
    if (d === void 0 || d.count !== s) {
      let H = function() {
        N.dispose(), n.delete(u), u.removeEventListener("dispose", H);
      };
      d !== void 0 && d.texture.dispose();
      const c = u.morphAttributes.position !== void 0, l = u.morphAttributes.normal !== void 0, W = u.morphAttributes.color !== void 0, X = u.morphAttributes.position || [], y = u.morphAttributes.normal || [], m = u.morphAttributes.color || [];
      let j = 0;
      c === true && (j = 1), l === true && (j = 2), W === true && (j = 3);
      let x = u.attributes.position.count * j, L = 1;
      x > e.maxTextureSize && (L = Math.ceil(x / e.maxTextureSize), x = e.maxTextureSize);
      const O = new Float32Array(x * L * 4 * s), N = new vK(O, x, L, s);
      N.type = Zz, N.needsUpdate = true;
      const i = j * 4;
      for (let M = 0; M < s; M++) {
        const p = X[M], T = y[M], w = m[M], Y = x * L * 4 * M;
        for (let q = 0; q < p.count; q++) {
          const D = q * i;
          c === true && (v.fromBufferAttribute(p, q), O[Y + D + 0] = v.x, O[Y + D + 1] = v.y, O[Y + D + 2] = v.z, O[Y + D + 3] = 0), l === true && (v.fromBufferAttribute(T, q), O[Y + D + 4] = v.x, O[Y + D + 5] = v.y, O[Y + D + 6] = v.z, O[Y + D + 7] = 0), W === true && (v.fromBufferAttribute(w, q), O[Y + D + 8] = v.x, O[Y + D + 9] = v.y, O[Y + D + 10] = v.z, O[Y + D + 11] = w.itemSize === 4 ? v.w : 1);
        }
      }
      d = { count: s, texture: N, size: new Me(x, L) }, n.set(u, d), u.addEventListener("dispose", H);
    }
    if (z.isInstancedMesh === true && z.morphTexture !== null) b.getUniforms().setValue(t, "morphTexture", z.morphTexture, f);
    else {
      let c = 0;
      for (let W = 0; W < a.length; W++) c += a[W];
      const l = u.morphTargetsRelative ? 1 : 1 - c;
      b.getUniforms().setValue(t, "morphTargetBaseInfluence", l), b.getUniforms().setValue(t, "morphTargetInfluences", a);
    }
    b.getUniforms().setValue(t, "morphTargetsTexture", d.texture, f), b.getUniforms().setValue(t, "morphTargetsTextureSize", d.size);
  }
  return { update: r };
}
function VJt(t, e, f, n) {
  let v = /* @__PURE__ */ new WeakMap();
  function r(b) {
    const a = n.render.frame, P = b.geometry, s = e.get(b, P);
    if (v.get(s) !== a && (e.update(s), v.set(s, a)), b.isInstancedMesh && (b.hasEventListener("dispose", u) === false && b.addEventListener("dispose", u), v.get(b) !== a && (f.update(b.instanceMatrix, t.ARRAY_BUFFER), b.instanceColor !== null && f.update(b.instanceColor, t.ARRAY_BUFFER), v.set(b, a))), b.isSkinnedMesh) {
      const d = b.skeleton;
      v.get(d) !== a && (d.update(), v.set(d, a));
    }
    return s;
  }
  function z() {
    v = /* @__PURE__ */ new WeakMap();
  }
  function u(b) {
    const a = b.target;
    a.removeEventListener("dispose", u), f.remove(a.instanceMatrix), a.instanceColor !== null && f.remove(a.instanceColor);
  }
  return { update: r, dispose: z };
}
const lE = new Tv();
const XE = new FK(1, 1);
const WE = new vK();
const yE = new tFt();
const ZE = new HK();
const mE = [];
const jE = [];
const xE = new Float32Array(16);
const LE = new Float32Array(9);
const OE = new Float32Array(4);
function y1(t, e, f) {
  const n = t[0];
  if (n <= 0 || n > 0) return t;
  const v = e * f;
  let r = mE[v];
  if (r === void 0 && (r = new Float32Array(v), mE[v] = r), e !== 0) {
    n.toArray(r, 0);
    for (let z = 1, u = 0; z !== e; ++z) u += f, t[z].toArray(r, u);
  }
  return r;
}
function Xv(t, e) {
  if (t.length !== e.length) return false;
  for (let f = 0, n = t.length; f < n; f++) if (t[f] !== e[f]) return false;
  return true;
}
function Wv(t, e) {
  for (let f = 0, n = e.length; f < n; f++) t[f] = e[f];
}
function C6(t, e) {
  let f = jE[e];
  f === void 0 && (f = new Int32Array(e), jE[e] = f);
  for (let n = 0; n !== e; ++n) f[n] = t.allocateTextureUnit();
  return f;
}
function YJt(t, e) {
  const f = this.cache;
  f[0] !== e && (t.uniform1f(this.addr, e), f[0] = e);
}
function DJt(t, e) {
  const f = this.cache;
  if (e.x !== void 0) (f[0] !== e.x || f[1] !== e.y) && (t.uniform2f(this.addr, e.x, e.y), f[0] = e.x, f[1] = e.y);
  else {
    if (Xv(f, e)) return;
    t.uniform2fv(this.addr, e), Wv(f, e);
  }
}
function qJt(t, e) {
  const f = this.cache;
  if (e.x !== void 0) (f[0] !== e.x || f[1] !== e.y || f[2] !== e.z) && (t.uniform3f(this.addr, e.x, e.y, e.z), f[0] = e.x, f[1] = e.y, f[2] = e.z);
  else if (e.r !== void 0) (f[0] !== e.r || f[1] !== e.g || f[2] !== e.b) && (t.uniform3f(this.addr, e.r, e.g, e.b), f[0] = e.r, f[1] = e.g, f[2] = e.b);
  else {
    if (Xv(f, e)) return;
    t.uniform3fv(this.addr, e), Wv(f, e);
  }
}
function FJt(t, e) {
  const f = this.cache;
  if (e.x !== void 0) (f[0] !== e.x || f[1] !== e.y || f[2] !== e.z || f[3] !== e.w) && (t.uniform4f(this.addr, e.x, e.y, e.z, e.w), f[0] = e.x, f[1] = e.y, f[2] = e.z, f[3] = e.w);
  else {
    if (Xv(f, e)) return;
    t.uniform4fv(this.addr, e), Wv(f, e);
  }
}
function RJt(t, e) {
  const f = this.cache, n = e.elements;
  if (n === void 0) {
    if (Xv(f, e)) return;
    t.uniformMatrix2fv(this.addr, false, e), Wv(f, e);
  } else {
    if (Xv(f, n)) return;
    OE.set(n), t.uniformMatrix2fv(this.addr, false, OE), Wv(f, n);
  }
}
function kJt(t, e) {
  const f = this.cache, n = e.elements;
  if (n === void 0) {
    if (Xv(f, e)) return;
    t.uniformMatrix3fv(this.addr, false, e), Wv(f, e);
  } else {
    if (Xv(f, n)) return;
    LE.set(n), t.uniformMatrix3fv(this.addr, false, LE), Wv(f, n);
  }
}
function JJt(t, e) {
  const f = this.cache, n = e.elements;
  if (n === void 0) {
    if (Xv(f, e)) return;
    t.uniformMatrix4fv(this.addr, false, e), Wv(f, e);
  } else {
    if (Xv(f, n)) return;
    xE.set(n), t.uniformMatrix4fv(this.addr, false, xE), Wv(f, n);
  }
}
function gJt(t, e) {
  const f = this.cache;
  f[0] !== e && (t.uniform1i(this.addr, e), f[0] = e);
}
function SJt(t, e) {
  const f = this.cache;
  if (e.x !== void 0) (f[0] !== e.x || f[1] !== e.y) && (t.uniform2i(this.addr, e.x, e.y), f[0] = e.x, f[1] = e.y);
  else {
    if (Xv(f, e)) return;
    t.uniform2iv(this.addr, e), Wv(f, e);
  }
}
function BJt(t, e) {
  const f = this.cache;
  if (e.x !== void 0) (f[0] !== e.x || f[1] !== e.y || f[2] !== e.z) && (t.uniform3i(this.addr, e.x, e.y, e.z), f[0] = e.x, f[1] = e.y, f[2] = e.z);
  else {
    if (Xv(f, e)) return;
    t.uniform3iv(this.addr, e), Wv(f, e);
  }
}
function UJt(t, e) {
  const f = this.cache;
  if (e.x !== void 0) (f[0] !== e.x || f[1] !== e.y || f[2] !== e.z || f[3] !== e.w) && (t.uniform4i(this.addr, e.x, e.y, e.z, e.w), f[0] = e.x, f[1] = e.y, f[2] = e.z, f[3] = e.w);
  else {
    if (Xv(f, e)) return;
    t.uniform4iv(this.addr, e), Wv(f, e);
  }
}
function IJt(t, e) {
  const f = this.cache;
  f[0] !== e && (t.uniform1ui(this.addr, e), f[0] = e);
}
function KJt(t, e) {
  const f = this.cache;
  if (e.x !== void 0) (f[0] !== e.x || f[1] !== e.y) && (t.uniform2ui(this.addr, e.x, e.y), f[0] = e.x, f[1] = e.y);
  else {
    if (Xv(f, e)) return;
    t.uniform2uiv(this.addr, e), Wv(f, e);
  }
}
function EJt(t, e) {
  const f = this.cache;
  if (e.x !== void 0) (f[0] !== e.x || f[1] !== e.y || f[2] !== e.z) && (t.uniform3ui(this.addr, e.x, e.y, e.z), f[0] = e.x, f[1] = e.y, f[2] = e.z);
  else {
    if (Xv(f, e)) return;
    t.uniform3uiv(this.addr, e), Wv(f, e);
  }
}
function CJt(t, e) {
  const f = this.cache;
  if (e.x !== void 0) (f[0] !== e.x || f[1] !== e.y || f[2] !== e.z || f[3] !== e.w) && (t.uniform4ui(this.addr, e.x, e.y, e.z, e.w), f[0] = e.x, f[1] = e.y, f[2] = e.z, f[3] = e.w);
  else {
    if (Xv(f, e)) return;
    t.uniform4uiv(this.addr, e), Wv(f, e);
  }
}
function AJt(t, e, f) {
  const n = this.cache, v = f.allocateTextureUnit();
  n[0] !== v && (t.uniform1i(this.addr, v), n[0] = v);
  let r;
  this.type === t.SAMPLER_2D_SHADOW ? (XE.compareFunction = AI, r = XE) : r = lE, f.setTexture2D(e || r, v);
}
function QJt(t, e, f) {
  const n = this.cache, v = f.allocateTextureUnit();
  n[0] !== v && (t.uniform1i(this.addr, v), n[0] = v), f.setTexture3D(e || yE, v);
}
function _Jt(t, e, f) {
  const n = this.cache, v = f.allocateTextureUnit();
  n[0] !== v && (t.uniform1i(this.addr, v), n[0] = v), f.setTextureCube(e || ZE, v);
}
function $Jt(t, e, f) {
  const n = this.cache, v = f.allocateTextureUnit();
  n[0] !== v && (t.uniform1i(this.addr, v), n[0] = v), f.setTexture2DArray(e || WE, v);
}
function tgt(t) {
  switch (t) {
    case 5126:
      return YJt;
    case 35664:
      return DJt;
    case 35665:
      return qJt;
    case 35666:
      return FJt;
    case 35674:
      return RJt;
    case 35675:
      return kJt;
    case 35676:
      return JJt;
    case 5124:
    case 35670:
      return gJt;
    case 35667:
    case 35671:
      return SJt;
    case 35668:
    case 35672:
      return BJt;
    case 35669:
    case 35673:
      return UJt;
    case 5125:
      return IJt;
    case 36294:
      return KJt;
    case 36295:
      return EJt;
    case 36296:
      return CJt;
    case 35678:
    case 36198:
    case 36298:
    case 36306:
    case 35682:
      return AJt;
    case 35679:
    case 36299:
    case 36307:
      return QJt;
    case 35680:
    case 36300:
    case 36308:
    case 36293:
      return _Jt;
    case 36289:
    case 36303:
    case 36311:
    case 36292:
      return $Jt;
  }
}
function egt(t, e) {
  t.uniform1fv(this.addr, e);
}
function fgt(t, e) {
  const f = y1(e, this.size, 2);
  t.uniform2fv(this.addr, f);
}
function ngt(t, e) {
  const f = y1(e, this.size, 3);
  t.uniform3fv(this.addr, f);
}
function vgt(t, e) {
  const f = y1(e, this.size, 4);
  t.uniform4fv(this.addr, f);
}
function rgt(t, e) {
  const f = y1(e, this.size, 4);
  t.uniformMatrix2fv(this.addr, false, f);
}
function zgt(t, e) {
  const f = y1(e, this.size, 9);
  t.uniformMatrix3fv(this.addr, false, f);
}
function ugt(t, e) {
  const f = y1(e, this.size, 16);
  t.uniformMatrix4fv(this.addr, false, f);
}
function bgt(t, e) {
  t.uniform1iv(this.addr, e);
}
function agt(t, e) {
  t.uniform2iv(this.addr, e);
}
function Pgt(t, e) {
  t.uniform3iv(this.addr, e);
}
function sgt(t, e) {
  t.uniform4iv(this.addr, e);
}
function dgt(t, e) {
  t.uniform1uiv(this.addr, e);
}
function cgt(t, e) {
  t.uniform2uiv(this.addr, e);
}
function lgt(t, e) {
  t.uniform3uiv(this.addr, e);
}
function Xgt(t, e) {
  t.uniform4uiv(this.addr, e);
}
function Wgt(t, e, f) {
  const n = this.cache, v = e.length, r = C6(f, v);
  Xv(n, r) || (t.uniform1iv(this.addr, r), Wv(n, r));
  for (let z = 0; z !== v; ++z) f.setTexture2D(e[z] || lE, r[z]);
}
function ygt(t, e, f) {
  const n = this.cache, v = e.length, r = C6(f, v);
  Xv(n, r) || (t.uniform1iv(this.addr, r), Wv(n, r));
  for (let z = 0; z !== v; ++z) f.setTexture3D(e[z] || yE, r[z]);
}
function Zgt(t, e, f) {
  const n = this.cache, v = e.length, r = C6(f, v);
  Xv(n, r) || (t.uniform1iv(this.addr, r), Wv(n, r));
  for (let z = 0; z !== v; ++z) f.setTextureCube(e[z] || ZE, r[z]);
}
function mgt(t, e, f) {
  const n = this.cache, v = e.length, r = C6(f, v);
  Xv(n, r) || (t.uniform1iv(this.addr, r), Wv(n, r));
  for (let z = 0; z !== v; ++z) f.setTexture2DArray(e[z] || WE, r[z]);
}
function jgt(t) {
  switch (t) {
    case 5126:
      return egt;
    case 35664:
      return fgt;
    case 35665:
      return ngt;
    case 35666:
      return vgt;
    case 35674:
      return rgt;
    case 35675:
      return zgt;
    case 35676:
      return ugt;
    case 5124:
    case 35670:
      return bgt;
    case 35667:
    case 35671:
      return agt;
    case 35668:
    case 35672:
      return Pgt;
    case 35669:
    case 35673:
      return sgt;
    case 5125:
      return dgt;
    case 36294:
      return cgt;
    case 36295:
      return lgt;
    case 36296:
      return Xgt;
    case 35678:
    case 36198:
    case 36298:
    case 36306:
    case 35682:
      return Wgt;
    case 35679:
    case 36299:
    case 36307:
      return ygt;
    case 35680:
    case 36300:
    case 36308:
    case 36293:
      return Zgt;
    case 36289:
    case 36303:
    case 36311:
    case 36292:
      return mgt;
  }
}
class xgt {
  constructor(e, f, n) {
    this.id = e, this.addr = n, this.cache = [], this.type = f.type, this.setValue = tgt(f.type);
  }
}
class Lgt {
  constructor(e, f, n) {
    this.id = e, this.addr = n, this.cache = [], this.type = f.type, this.size = f.size, this.setValue = jgt(f.type);
  }
}
class Ogt {
  constructor(e) {
    this.id = e, this.seq = [], this.map = {};
  }
  setValue(e, f, n) {
    const v = this.seq;
    for (let r = 0, z = v.length; r !== z; ++r) {
      const u = v[r];
      u.setValue(e, f[u.id], n);
    }
  }
}
const AM = /(\w+)(\])?(\[|\.)?/g;
function HE(t, e) {
  t.seq.push(e), t.map[e.id] = e;
}
function Hgt(t, e, f) {
  const n = t.name, v = n.length;
  for (AM.lastIndex = 0; ; ) {
    const r = AM.exec(n), z = AM.lastIndex;
    let u = r[1];
    const b = r[2] === "]", a = r[3];
    if (b && (u = u | 0), a === void 0 || a === "[" && z + 2 === v) {
      HE(f, a === void 0 ? new xgt(u, t, e) : new Lgt(u, t, e));
      break;
    } else {
      let s = f.map[u];
      s === void 0 && (s = new Ogt(u), HE(f, s)), f = s;
    }
  }
}
class A6 {
  constructor(e, f) {
    this.seq = [], this.map = {};
    const n = e.getProgramParameter(f, e.ACTIVE_UNIFORMS);
    for (let v = 0; v < n; ++v) {
      const r = e.getActiveUniform(f, v), z = e.getUniformLocation(f, r.name);
      Hgt(r, z, this);
    }
  }
  setValue(e, f, n, v) {
    const r = this.map[f];
    r !== void 0 && r.setValue(e, n, v);
  }
  setOptional(e, f, n) {
    const v = f[n];
    v !== void 0 && this.setValue(e, n, v);
  }
  static upload(e, f, n, v) {
    for (let r = 0, z = f.length; r !== z; ++r) {
      const u = f[r], b = n[u.id];
      b.needsUpdate !== false && u.setValue(e, b.value, v);
    }
  }
  static seqWithValue(e, f) {
    const n = [];
    for (let v = 0, r = e.length; v !== r; ++v) {
      const z = e[v];
      z.id in f && n.push(z);
    }
    return n;
  }
}
function NE(t, e, f) {
  const n = t.createShader(e);
  return t.shaderSource(n, f), t.compileShader(n), n;
}
const Ngt = 37297;
let Mgt = 0;
function igt(t, e) {
  const f = t.split(`
`), n = [], v = Math.max(e - 6, 0), r = Math.min(e + 6, f.length);
  for (let z = v; z < r; z++) {
    const u = z + 1;
    n.push(`${u === e ? ">" : " "} ${u}: ${f[z]}`);
  }
  return n.join(`
`);
}
const ME = new Tf();
function pgt(t) {
  Uf._getMatrix(ME, Uf.workingColorSpace, t);
  const e = `mat3( ${ME.elements.map((f) => f.toFixed(4))} )`;
  switch (Uf.getTransfer(t)) {
    case u6:
      return [e, "LinearTransferOETF"];
    case dn:
      return [e, "sRGBTransferOETF"];
    default:
      return console.warn("THREE.WebGLProgram: Unsupported color space: ", t), [e, "LinearTransferOETF"];
  }
}
function iE(t, e, f) {
  const n = t.getShaderParameter(e, t.COMPILE_STATUS), r = (t.getShaderInfoLog(e) || "").trim();
  if (n && r === "") return "";
  const z = /ERROR: 0:(\d+)/.exec(r);
  if (z) {
    const u = parseInt(z[1]);
    return f.toUpperCase() + `

` + r + `

` + igt(t.getShaderSource(e), u);
  } else return r;
}
function Tgt(t, e) {
  const f = pgt(e);
  return [`vec4 ${t}( vec4 value ) {`, `	return ${f[1]}( vec4( value.rgb * ${f[0]}, value.a ) );`, "}"].join(`
`);
}
function ogt(t, e) {
  let f;
  switch (e) {
    case lqt:
      f = "Linear";
      break;
    case Xqt:
      f = "Reinhard";
      break;
    case Wqt:
      f = "Cineon";
      break;
    case RI:
      f = "ACESFilmic";
      break;
    case Zqt:
      f = "AgX";
      break;
    case mqt:
      f = "Neutral";
      break;
    case yqt:
      f = "Custom";
      break;
    default:
      console.warn("THREE.WebGLProgram: Unsupported toneMapping:", e), f = "Linear";
  }
  return "vec3 " + t + "( vec3 color ) { return " + f + "ToneMapping( color ); }";
}
const Q6 = new ht();
function wgt() {
  Uf.getLuminanceCoefficients(Q6);
  const t = Q6.x.toFixed(4), e = Q6.y.toFixed(4), f = Q6.z.toFixed(4);
  return ["float luminance( const in vec3 rgb ) {", `	const vec3 weights = vec3( ${t}, ${e}, ${f} );`, "	return dot( weights, rgb );", "}"].join(`
`);
}
function hgt(t) {
  return [t.extensionClipCullDistance ? "#extension GL_ANGLE_clip_cull_distance : require" : "", t.extensionMultiDraw ? "#extension GL_ANGLE_multi_draw : require" : ""].filter(Qs).join(`
`);
}
function Ggt(t) {
  const e = [];
  for (const f in t) {
    const n = t[f];
    n !== false && e.push("#define " + f + " " + n);
  }
  return e.join(`
`);
}
function Vgt(t, e) {
  const f = {}, n = t.getProgramParameter(e, t.ACTIVE_ATTRIBUTES);
  for (let v = 0; v < n; v++) {
    const r = t.getActiveAttrib(e, v), z = r.name;
    let u = 1;
    r.type === t.FLOAT_MAT2 && (u = 2), r.type === t.FLOAT_MAT3 && (u = 3), r.type === t.FLOAT_MAT4 && (u = 4), f[z] = { type: r.type, location: t.getAttribLocation(e, z), locationSize: u };
  }
  return f;
}
function Qs(t) {
  return t !== "";
}
function pE(t, e) {
  const f = e.numSpotLightShadows + e.numSpotLightMaps - e.numSpotLightShadowsWithMaps;
  return t.replace(/NUM_DIR_LIGHTS/g, e.numDirLights).replace(/NUM_SPOT_LIGHTS/g, e.numSpotLights).replace(/NUM_SPOT_LIGHT_MAPS/g, e.numSpotLightMaps).replace(/NUM_SPOT_LIGHT_COORDS/g, f).replace(/NUM_RECT_AREA_LIGHTS/g, e.numRectAreaLights).replace(/NUM_POINT_LIGHTS/g, e.numPointLights).replace(/NUM_HEMI_LIGHTS/g, e.numHemiLights).replace(/NUM_DIR_LIGHT_SHADOWS/g, e.numDirLightShadows).replace(/NUM_SPOT_LIGHT_SHADOWS_WITH_MAPS/g, e.numSpotLightShadowsWithMaps).replace(/NUM_SPOT_LIGHT_SHADOWS/g, e.numSpotLightShadows).replace(/NUM_POINT_LIGHT_SHADOWS/g, e.numPointLightShadows);
}
function TE(t, e) {
  return t.replace(/NUM_CLIPPING_PLANES/g, e.numClippingPlanes).replace(/UNION_CLIPPING_PLANES/g, e.numClippingPlanes - e.numClipIntersection);
}
const Ygt = /^[ \t]*#include +<([\w\d./]+)>/gm;
function QM(t) {
  return t.replace(Ygt, qgt);
}
const Dgt = /* @__PURE__ */ new Map();
function qgt(t, e) {
  let f = of[e];
  if (f === void 0) {
    const n = Dgt.get(e);
    if (n !== void 0) f = of[n], console.warn('THREE.WebGLRenderer: Shader chunk "%s" has been deprecated. Use "%s" instead.', e, n);
    else throw new Error("Can not resolve #include <" + e + ">");
  }
  return QM(f);
}
const Fgt = /#pragma unroll_loop_start\s+for\s*\(\s*int\s+i\s*=\s*(\d+)\s*;\s*i\s*<\s*(\d+)\s*;\s*i\s*\+\+\s*\)\s*{([\s\S]+?)}\s+#pragma unroll_loop_end/g;
function oE(t) {
  return t.replace(Fgt, Rgt);
}
function Rgt(t, e, f, n) {
  let v = "";
  for (let r = parseInt(e); r < parseInt(f); r++) v += n.replace(/\[\s*i\s*\]/g, "[ " + r + " ]").replace(/UNROLLED_LOOP_INDEX/g, r);
  return v;
}
function wE(t) {
  let e = `precision ${t.precision} float;
	precision ${t.precision} int;
	precision ${t.precision} sampler2D;
	precision ${t.precision} samplerCube;
	precision ${t.precision} sampler3D;
	precision ${t.precision} sampler2DArray;
	precision ${t.precision} sampler2DShadow;
	precision ${t.precision} samplerCubeShadow;
	precision ${t.precision} sampler2DArrayShadow;
	precision ${t.precision} isampler2D;
	precision ${t.precision} isampler3D;
	precision ${t.precision} isamplerCube;
	precision ${t.precision} isampler2DArray;
	precision ${t.precision} usampler2D;
	precision ${t.precision} usampler3D;
	precision ${t.precision} usamplerCube;
	precision ${t.precision} usampler2DArray;
	`;
  return t.precision === "highp" ? e += `
#define HIGH_PRECISION` : t.precision === "mediump" ? e += `
#define MEDIUM_PRECISION` : t.precision === "lowp" && (e += `
#define LOW_PRECISION`), e;
}
function kgt(t) {
  let e = "SHADOWMAP_TYPE_BASIC";
  return t.shadowMapType === GI ? e = "SHADOWMAP_TYPE_PCF" : t.shadowMapType === VI ? e = "SHADOWMAP_TYPE_PCF_SOFT" : t.shadowMapType === s9 && (e = "SHADOWMAP_TYPE_VSM"), e;
}
function Jgt(t) {
  let e = "ENVMAP_TYPE_CUBE";
  if (t.envMap) switch (t.envMapMode) {
    case ga:
    case Sa:
      e = "ENVMAP_TYPE_CUBE";
      break;
    case t6:
      e = "ENVMAP_TYPE_CUBE_UV";
      break;
  }
  return e;
}
function ggt(t) {
  let e = "ENVMAP_MODE_REFLECTION";
  if (t.envMap) switch (t.envMapMode) {
    case Sa:
      e = "ENVMAP_MODE_REFRACTION";
      break;
  }
  return e;
}
function Sgt(t) {
  let e = "ENVMAP_BLENDING_NONE";
  if (t.envMap) switch (t.combine) {
    case FI:
      e = "ENVMAP_BLENDING_MULTIPLY";
      break;
    case dqt:
      e = "ENVMAP_BLENDING_MIX";
      break;
    case cqt:
      e = "ENVMAP_BLENDING_ADD";
      break;
  }
  return e;
}
function Bgt(t) {
  const e = t.envMapCubeUVHeight;
  if (e === null) return null;
  const f = Math.log2(e) - 2, n = 1 / e;
  return { texelWidth: 1 / (3 * Math.max(Math.pow(2, f), 7 * 16)), texelHeight: n, maxMip: f };
}
function Ugt(t, e, f, n) {
  const v = t.getContext(), r = f.defines;
  let z = f.vertexShader, u = f.fragmentShader;
  const b = kgt(f), a = Jgt(f), P = ggt(f), s = Sgt(f), d = Bgt(f), c = hgt(f), l = Ggt(r), W = v.createProgram();
  let X, y, m = f.glslVersion ? "#version " + f.glslVersion + `
` : "";
  f.isRawShaderMaterial ? (X = ["#define SHADER_TYPE " + f.shaderType, "#define SHADER_NAME " + f.shaderName, l].filter(Qs).join(`
`), X.length > 0 && (X += `
`), y = ["#define SHADER_TYPE " + f.shaderType, "#define SHADER_NAME " + f.shaderName, l].filter(Qs).join(`
`), y.length > 0 && (y += `
`)) : (X = [wE(f), "#define SHADER_TYPE " + f.shaderType, "#define SHADER_NAME " + f.shaderName, l, f.extensionClipCullDistance ? "#define USE_CLIP_DISTANCE" : "", f.batching ? "#define USE_BATCHING" : "", f.batchingColor ? "#define USE_BATCHING_COLOR" : "", f.instancing ? "#define USE_INSTANCING" : "", f.instancingColor ? "#define USE_INSTANCING_COLOR" : "", f.instancingMorph ? "#define USE_INSTANCING_MORPH" : "", f.useFog && f.fog ? "#define USE_FOG" : "", f.useFog && f.fogExp2 ? "#define FOG_EXP2" : "", f.map ? "#define USE_MAP" : "", f.envMap ? "#define USE_ENVMAP" : "", f.envMap ? "#define " + P : "", f.lightMap ? "#define USE_LIGHTMAP" : "", f.aoMap ? "#define USE_AOMAP" : "", f.bumpMap ? "#define USE_BUMPMAP" : "", f.normalMap ? "#define USE_NORMALMAP" : "", f.normalMapObjectSpace ? "#define USE_NORMALMAP_OBJECTSPACE" : "", f.normalMapTangentSpace ? "#define USE_NORMALMAP_TANGENTSPACE" : "", f.displacementMap ? "#define USE_DISPLACEMENTMAP" : "", f.emissiveMap ? "#define USE_EMISSIVEMAP" : "", f.anisotropy ? "#define USE_ANISOTROPY" : "", f.anisotropyMap ? "#define USE_ANISOTROPYMAP" : "", f.clearcoatMap ? "#define USE_CLEARCOATMAP" : "", f.clearcoatRoughnessMap ? "#define USE_CLEARCOAT_ROUGHNESSMAP" : "", f.clearcoatNormalMap ? "#define USE_CLEARCOAT_NORMALMAP" : "", f.iridescenceMap ? "#define USE_IRIDESCENCEMAP" : "", f.iridescenceThicknessMap ? "#define USE_IRIDESCENCE_THICKNESSMAP" : "", f.specularMap ? "#define USE_SPECULARMAP" : "", f.specularColorMap ? "#define USE_SPECULAR_COLORMAP" : "", f.specularIntensityMap ? "#define USE_SPECULAR_INTENSITYMAP" : "", f.roughnessMap ? "#define USE_ROUGHNESSMAP" : "", f.metalnessMap ? "#define USE_METALNESSMAP" : "", f.alphaMap ? "#define USE_ALPHAMAP" : "", f.alphaHash ? "#define USE_ALPHAHASH" : "", f.transmission ? "#define USE_TRANSMISSION" : "", f.transmissionMap ? "#define USE_TRANSMISSIONMAP" : "", f.thicknessMap ? "#define USE_THICKNESSMAP" : "", f.sheenColorMap ? "#define USE_SHEEN_COLORMAP" : "", f.sheenRoughnessMap ? "#define USE_SHEEN_ROUGHNESSMAP" : "", f.mapUv ? "#define MAP_UV " + f.mapUv : "", f.alphaMapUv ? "#define ALPHAMAP_UV " + f.alphaMapUv : "", f.lightMapUv ? "#define LIGHTMAP_UV " + f.lightMapUv : "", f.aoMapUv ? "#define AOMAP_UV " + f.aoMapUv : "", f.emissiveMapUv ? "#define EMISSIVEMAP_UV " + f.emissiveMapUv : "", f.bumpMapUv ? "#define BUMPMAP_UV " + f.bumpMapUv : "", f.normalMapUv ? "#define NORMALMAP_UV " + f.normalMapUv : "", f.displacementMapUv ? "#define DISPLACEMENTMAP_UV " + f.displacementMapUv : "", f.metalnessMapUv ? "#define METALNESSMAP_UV " + f.metalnessMapUv : "", f.roughnessMapUv ? "#define ROUGHNESSMAP_UV " + f.roughnessMapUv : "", f.anisotropyMapUv ? "#define ANISOTROPYMAP_UV " + f.anisotropyMapUv : "", f.clearcoatMapUv ? "#define CLEARCOATMAP_UV " + f.clearcoatMapUv : "", f.clearcoatNormalMapUv ? "#define CLEARCOAT_NORMALMAP_UV " + f.clearcoatNormalMapUv : "", f.clearcoatRoughnessMapUv ? "#define CLEARCOAT_ROUGHNESSMAP_UV " + f.clearcoatRoughnessMapUv : "", f.iridescenceMapUv ? "#define IRIDESCENCEMAP_UV " + f.iridescenceMapUv : "", f.iridescenceThicknessMapUv ? "#define IRIDESCENCE_THICKNESSMAP_UV " + f.iridescenceThicknessMapUv : "", f.sheenColorMapUv ? "#define SHEEN_COLORMAP_UV " + f.sheenColorMapUv : "", f.sheenRoughnessMapUv ? "#define SHEEN_ROUGHNESSMAP_UV " + f.sheenRoughnessMapUv : "", f.specularMapUv ? "#define SPECULARMAP_UV " + f.specularMapUv : "", f.specularColorMapUv ? "#define SPECULAR_COLORMAP_UV " + f.specularColorMapUv : "", f.specularIntensityMapUv ? "#define SPECULAR_INTENSITYMAP_UV " + f.specularIntensityMapUv : "", f.transmissionMapUv ? "#define TRANSMISSIONMAP_UV " + f.transmissionMapUv : "", f.thicknessMapUv ? "#define THICKNESSMAP_UV " + f.thicknessMapUv : "", f.vertexTangents && f.flatShading === false ? "#define USE_TANGENT" : "", f.vertexColors ? "#define USE_COLOR" : "", f.vertexAlphas ? "#define USE_COLOR_ALPHA" : "", f.vertexUv1s ? "#define USE_UV1" : "", f.vertexUv2s ? "#define USE_UV2" : "", f.vertexUv3s ? "#define USE_UV3" : "", f.pointsUvs ? "#define USE_POINTS_UV" : "", f.flatShading ? "#define FLAT_SHADED" : "", f.skinning ? "#define USE_SKINNING" : "", f.morphTargets ? "#define USE_MORPHTARGETS" : "", f.morphNormals && f.flatShading === false ? "#define USE_MORPHNORMALS" : "", f.morphColors ? "#define USE_MORPHCOLORS" : "", f.morphTargetsCount > 0 ? "#define MORPHTARGETS_TEXTURE_STRIDE " + f.morphTextureStride : "", f.morphTargetsCount > 0 ? "#define MORPHTARGETS_COUNT " + f.morphTargetsCount : "", f.doubleSided ? "#define DOUBLE_SIDED" : "", f.flipSided ? "#define FLIP_SIDED" : "", f.shadowMapEnabled ? "#define USE_SHADOWMAP" : "", f.shadowMapEnabled ? "#define " + b : "", f.sizeAttenuation ? "#define USE_SIZEATTENUATION" : "", f.numLightProbes > 0 ? "#define USE_LIGHT_PROBES" : "", f.logarithmicDepthBuffer ? "#define USE_LOGARITHMIC_DEPTH_BUFFER" : "", f.reversedDepthBuffer ? "#define USE_REVERSED_DEPTH_BUFFER" : "", "uniform mat4 modelMatrix;", "uniform mat4 modelViewMatrix;", "uniform mat4 projectionMatrix;", "uniform mat4 viewMatrix;", "uniform mat3 normalMatrix;", "uniform vec3 cameraPosition;", "uniform bool isOrthographic;", "#ifdef USE_INSTANCING", "	attribute mat4 instanceMatrix;", "#endif", "#ifdef USE_INSTANCING_COLOR", "	attribute vec3 instanceColor;", "#endif", "#ifdef USE_INSTANCING_MORPH", "	uniform sampler2D morphTexture;", "#endif", "attribute vec3 position;", "attribute vec3 normal;", "attribute vec2 uv;", "#ifdef USE_UV1", "	attribute vec2 uv1;", "#endif", "#ifdef USE_UV2", "	attribute vec2 uv2;", "#endif", "#ifdef USE_UV3", "	attribute vec2 uv3;", "#endif", "#ifdef USE_TANGENT", "	attribute vec4 tangent;", "#endif", "#if defined( USE_COLOR_ALPHA )", "	attribute vec4 color;", "#elif defined( USE_COLOR )", "	attribute vec3 color;", "#endif", "#ifdef USE_SKINNING", "	attribute vec4 skinIndex;", "	attribute vec4 skinWeight;", "#endif", `
`].filter(Qs).join(`
`), y = [wE(f), "#define SHADER_TYPE " + f.shaderType, "#define SHADER_NAME " + f.shaderName, l, f.useFog && f.fog ? "#define USE_FOG" : "", f.useFog && f.fogExp2 ? "#define FOG_EXP2" : "", f.alphaToCoverage ? "#define ALPHA_TO_COVERAGE" : "", f.map ? "#define USE_MAP" : "", f.matcap ? "#define USE_MATCAP" : "", f.envMap ? "#define USE_ENVMAP" : "", f.envMap ? "#define " + a : "", f.envMap ? "#define " + P : "", f.envMap ? "#define " + s : "", d ? "#define CUBEUV_TEXEL_WIDTH " + d.texelWidth : "", d ? "#define CUBEUV_TEXEL_HEIGHT " + d.texelHeight : "", d ? "#define CUBEUV_MAX_MIP " + d.maxMip + ".0" : "", f.lightMap ? "#define USE_LIGHTMAP" : "", f.aoMap ? "#define USE_AOMAP" : "", f.bumpMap ? "#define USE_BUMPMAP" : "", f.normalMap ? "#define USE_NORMALMAP" : "", f.normalMapObjectSpace ? "#define USE_NORMALMAP_OBJECTSPACE" : "", f.normalMapTangentSpace ? "#define USE_NORMALMAP_TANGENTSPACE" : "", f.emissiveMap ? "#define USE_EMISSIVEMAP" : "", f.anisotropy ? "#define USE_ANISOTROPY" : "", f.anisotropyMap ? "#define USE_ANISOTROPYMAP" : "", f.clearcoat ? "#define USE_CLEARCOAT" : "", f.clearcoatMap ? "#define USE_CLEARCOATMAP" : "", f.clearcoatRoughnessMap ? "#define USE_CLEARCOAT_ROUGHNESSMAP" : "", f.clearcoatNormalMap ? "#define USE_CLEARCOAT_NORMALMAP" : "", f.dispersion ? "#define USE_DISPERSION" : "", f.iridescence ? "#define USE_IRIDESCENCE" : "", f.iridescenceMap ? "#define USE_IRIDESCENCEMAP" : "", f.iridescenceThicknessMap ? "#define USE_IRIDESCENCE_THICKNESSMAP" : "", f.specularMap ? "#define USE_SPECULARMAP" : "", f.specularColorMap ? "#define USE_SPECULAR_COLORMAP" : "", f.specularIntensityMap ? "#define USE_SPECULAR_INTENSITYMAP" : "", f.roughnessMap ? "#define USE_ROUGHNESSMAP" : "", f.metalnessMap ? "#define USE_METALNESSMAP" : "", f.alphaMap ? "#define USE_ALPHAMAP" : "", f.alphaTest ? "#define USE_ALPHATEST" : "", f.alphaHash ? "#define USE_ALPHAHASH" : "", f.sheen ? "#define USE_SHEEN" : "", f.sheenColorMap ? "#define USE_SHEEN_COLORMAP" : "", f.sheenRoughnessMap ? "#define USE_SHEEN_ROUGHNESSMAP" : "", f.transmission ? "#define USE_TRANSMISSION" : "", f.transmissionMap ? "#define USE_TRANSMISSIONMAP" : "", f.thicknessMap ? "#define USE_THICKNESSMAP" : "", f.vertexTangents && f.flatShading === false ? "#define USE_TANGENT" : "", f.vertexColors || f.instancingColor || f.batchingColor ? "#define USE_COLOR" : "", f.vertexAlphas ? "#define USE_COLOR_ALPHA" : "", f.vertexUv1s ? "#define USE_UV1" : "", f.vertexUv2s ? "#define USE_UV2" : "", f.vertexUv3s ? "#define USE_UV3" : "", f.pointsUvs ? "#define USE_POINTS_UV" : "", f.gradientMap ? "#define USE_GRADIENTMAP" : "", f.flatShading ? "#define FLAT_SHADED" : "", f.doubleSided ? "#define DOUBLE_SIDED" : "", f.flipSided ? "#define FLIP_SIDED" : "", f.shadowMapEnabled ? "#define USE_SHADOWMAP" : "", f.shadowMapEnabled ? "#define " + b : "", f.premultipliedAlpha ? "#define PREMULTIPLIED_ALPHA" : "", f.numLightProbes > 0 ? "#define USE_LIGHT_PROBES" : "", f.decodeVideoTexture ? "#define DECODE_VIDEO_TEXTURE" : "", f.decodeVideoTextureEmissive ? "#define DECODE_VIDEO_TEXTURE_EMISSIVE" : "", f.logarithmicDepthBuffer ? "#define USE_LOGARITHMIC_DEPTH_BUFFER" : "", f.reversedDepthBuffer ? "#define USE_REVERSED_DEPTH_BUFFER" : "", "uniform mat4 viewMatrix;", "uniform vec3 cameraPosition;", "uniform bool isOrthographic;", f.toneMapping !== p5 ? "#define TONE_MAPPING" : "", f.toneMapping !== p5 ? of.tonemapping_pars_fragment : "", f.toneMapping !== p5 ? ogt("toneMapping", f.toneMapping) : "", f.dithering ? "#define DITHERING" : "", f.opaque ? "#define OPAQUE" : "", of.colorspace_pars_fragment, Tgt("linearToOutputTexel", f.outputColorSpace), wgt(), f.useDepthPacking ? "#define DEPTH_PACKING " + f.depthPacking : "", `
`].filter(Qs).join(`
`)), z = QM(z), z = pE(z, f), z = TE(z, f), u = QM(u), u = pE(u, f), u = TE(u, f), z = oE(z), u = oE(u), f.isRawShaderMaterial !== true && (m = `#version 300 es
`, X = [c, "#define attribute in", "#define varying out", "#define texture2D texture"].join(`
`) + `
` + X, y = ["#define varying in", f.glslVersion === QI ? "" : "layout(location = 0) out highp vec4 pc_fragColor;", f.glslVersion === QI ? "" : "#define gl_FragColor pc_fragColor", "#define gl_FragDepthEXT gl_FragDepth", "#define texture2D texture", "#define textureCube texture", "#define texture2DProj textureProj", "#define texture2DLodEXT textureLod", "#define texture2DProjLodEXT textureProjLod", "#define textureCubeLodEXT textureLod", "#define texture2DGradEXT textureGrad", "#define texture2DProjGradEXT textureProjGrad", "#define textureCubeGradEXT textureGrad"].join(`
`) + `
` + y);
  const j = m + X + z, x = m + y + u, L = NE(v, v.VERTEX_SHADER, j), O = NE(v, v.FRAGMENT_SHADER, x);
  v.attachShader(W, L), v.attachShader(W, O), f.index0AttributeName !== void 0 ? v.bindAttribLocation(W, 0, f.index0AttributeName) : f.morphTargets === true && v.bindAttribLocation(W, 0, "position"), v.linkProgram(W);
  function N(p) {
    if (t.debug.checkShaderErrors) {
      const T = v.getProgramInfoLog(W) || "", w = v.getShaderInfoLog(L) || "", Y = v.getShaderInfoLog(O) || "", q = T.trim(), D = w.trim(), V = Y.trim();
      let G = true, R = true;
      if (v.getProgramParameter(W, v.LINK_STATUS) === false) if (G = false, typeof t.debug.onShaderError == "function") t.debug.onShaderError(v, W, L, O);
      else {
        const k = iE(v, L, "vertex"), K = iE(v, O, "fragment");
        console.error("THREE.WebGLProgram: Shader Error " + v.getError() + " - VALIDATE_STATUS " + v.getProgramParameter(W, v.VALIDATE_STATUS) + `

Material Name: ` + p.name + `
Material Type: ` + p.type + `

Program Info Log: ` + q + `
` + k + `
` + K);
      }
      else q !== "" ? console.warn("THREE.WebGLProgram: Program Info Log:", q) : (D === "" || V === "") && (R = false);
      R && (p.diagnostics = { runnable: G, programLog: q, vertexShader: { log: D, prefix: X }, fragmentShader: { log: V, prefix: y } });
    }
    v.deleteShader(L), v.deleteShader(O), i = new A6(v, W), H = Vgt(v, W);
  }
  let i;
  this.getUniforms = function() {
    return i === void 0 && N(this), i;
  };
  let H;
  this.getAttributes = function() {
    return H === void 0 && N(this), H;
  };
  let M = f.rendererExtensionParallelShaderCompile === false;
  return this.isReady = function() {
    return M === false && (M = v.getProgramParameter(W, Ngt)), M;
  }, this.destroy = function() {
    n.releaseStatesOfProgram(this), v.deleteProgram(W), this.program = void 0;
  }, this.type = f.shaderType, this.name = f.shaderName, this.id = Mgt++, this.cacheKey = e, this.usedTimes = 1, this.program = W, this.vertexShader = L, this.fragmentShader = O, this;
}
let Igt = 0;
class Kgt {
  constructor() {
    this.shaderCache = /* @__PURE__ */ new Map(), this.materialCache = /* @__PURE__ */ new Map();
  }
  update(e) {
    const f = e.vertexShader, n = e.fragmentShader, v = this._getShaderStage(f), r = this._getShaderStage(n), z = this._getShaderCacheForMaterial(e);
    return z.has(v) === false && (z.add(v), v.usedTimes++), z.has(r) === false && (z.add(r), r.usedTimes++), this;
  }
  remove(e) {
    const f = this.materialCache.get(e);
    for (const n of f) n.usedTimes--, n.usedTimes === 0 && this.shaderCache.delete(n.code);
    return this.materialCache.delete(e), this;
  }
  getVertexShaderID(e) {
    return this._getShaderStage(e.vertexShader).id;
  }
  getFragmentShaderID(e) {
    return this._getShaderStage(e.fragmentShader).id;
  }
  dispose() {
    this.shaderCache.clear(), this.materialCache.clear();
  }
  _getShaderCacheForMaterial(e) {
    const f = this.materialCache;
    let n = f.get(e);
    return n === void 0 && (n = /* @__PURE__ */ new Set(), f.set(e, n)), n;
  }
  _getShaderStage(e) {
    const f = this.shaderCache;
    let n = f.get(e);
    return n === void 0 && (n = new Egt(e), f.set(e, n)), n;
  }
}
class Egt {
  constructor(e) {
    this.id = Igt++, this.code = e, this.usedTimes = 0;
  }
}
function Cgt(t, e, f, n, v, r, z) {
  const u = new PM(), b = new Kgt(), a = /* @__PURE__ */ new Set(), P = [], s = v.logarithmicDepthBuffer, d = v.vertexTextures;
  let c = v.precision;
  const l = { MeshDepthMaterial: "depth", MeshDistanceMaterial: "distanceRGBA", MeshNormalMaterial: "normal", MeshBasicMaterial: "basic", MeshLambertMaterial: "lambert", MeshPhongMaterial: "phong", MeshToonMaterial: "toon", MeshStandardMaterial: "physical", MeshPhysicalMaterial: "physical", MeshMatcapMaterial: "matcap", LineBasicMaterial: "basic", LineDashedMaterial: "dashed", PointsMaterial: "points", ShadowMaterial: "shadow", SpriteMaterial: "sprite" };
  function W(H) {
    return a.add(H), H === 0 ? "uv" : `uv${H}`;
  }
  function X(H, M, p, T, w) {
    const Y = T.fog, q = w.geometry, D = H.isMeshStandardMaterial ? T.environment : null, V = (H.isMeshStandardMaterial ? f : e).get(H.envMap || D), G = V && V.mapping === t6 ? V.image.height : null, R = l[H.type];
    H.precision !== null && (c = v.getMaxPrecision(H.precision), c !== H.precision && console.warn("THREE.WebGLProgram.getParameters:", H.precision, "not supported, using", c, "instead."));
    const k = q.morphAttributes.position || q.morphAttributes.normal || q.morphAttributes.color, K = k !== void 0 ? k.length : 0;
    let _ = 0;
    q.morphAttributes.position !== void 0 && (_ = 1), q.morphAttributes.normal !== void 0 && (_ = 2), q.morphAttributes.color !== void 0 && (_ = 3);
    let vt, rt, A, Q;
    if (R) {
      const Qt = Hz[R];
      vt = Qt.vertexShader, rt = Qt.fragmentShader;
    } else vt = H.vertexShader, rt = H.fragmentShader, b.update(H), A = b.getVertexShaderID(H), Q = b.getFragmentShaderID(H);
    const B = t.getRenderTarget(), et = t.state.buffers.depth.getReversed(), nt = w.isInstancedMesh === true, I = w.isBatchedMesh === true, U = !!H.map, J = !!H.matcap, g = !!V, tt = !!H.aoMap, zt = !!H.lightMap, ut = !!H.bumpMap, Pt = !!H.normalMap, xt = !!H.displacementMap, Lt = !!H.emissiveMap, Mt = !!H.metalnessMap, Ht = !!H.roughnessMap, Gt = H.anisotropy > 0, ft = H.clearcoat > 0, $ = H.dispersion > 0, yt = H.iridescence > 0, Tt = H.sheen > 0, Dt = H.transmission > 0, pt = Gt && !!H.anisotropyMap, ce = ft && !!H.clearcoatMap, ee = ft && !!H.clearcoatNormalMap, xe = ft && !!H.clearcoatRoughnessMap, me = yt && !!H.iridescenceMap, ne = yt && !!H.iridescenceThicknessMap, Le = Tt && !!H.sheenColorMap, wt = Tt && !!H.sheenRoughnessMap, it = !!H.specularMap, Rt = !!H.specularColorMap, jt = !!H.specularIntensityMap, Xt = Dt && !!H.transmissionMap, ot = Dt && !!H.thicknessMap, Ut = !!H.gradientMap, te = !!H.alphaMap, It = H.alphaTest > 0, Bt = !!H.alphaHash, de = !!H.extensions;
    let ve = p5;
    H.toneMapped && (B === null || B.isXRRenderTarget === true) && (ve = t.toneMapping);
    const Re = { shaderID: R, shaderType: H.type, shaderName: H.name, vertexShader: vt, fragmentShader: rt, defines: H.defines, customVertexShaderID: A, customFragmentShaderID: Q, isRawShaderMaterial: H.isRawShaderMaterial === true, glslVersion: H.glslVersion, precision: c, batching: I, batchingColor: I && w._colorsTexture !== null, instancing: nt, instancingColor: nt && w.instanceColor !== null, instancingMorph: nt && w.morphTexture !== null, supportsVertexTextures: d, outputColorSpace: B === null ? t.outputColorSpace : B.isXRRenderTarget === true ? B.texture.colorSpace : Ua, alphaToCoverage: !!H.alphaToCoverage, map: U, matcap: J, envMap: g, envMapMode: g && V.mapping, envMapCubeUVHeight: G, aoMap: tt, lightMap: zt, bumpMap: ut, normalMap: Pt, displacementMap: d && xt, emissiveMap: Lt, normalMapObjectSpace: Pt && H.normalMapType === Oqt, normalMapTangentSpace: Pt && H.normalMapType === EI, metalnessMap: Mt, roughnessMap: Ht, anisotropy: Gt, anisotropyMap: pt, clearcoat: ft, clearcoatMap: ce, clearcoatNormalMap: ee, clearcoatRoughnessMap: xe, dispersion: $, iridescence: yt, iridescenceMap: me, iridescenceThicknessMap: ne, sheen: Tt, sheenColorMap: Le, sheenRoughnessMap: wt, specularMap: it, specularColorMap: Rt, specularIntensityMap: jt, transmission: Dt, transmissionMap: Xt, thicknessMap: ot, gradientMap: Ut, opaque: H.transparent === false && H.blending === ka && H.alphaToCoverage === false, alphaMap: te, alphaTest: It, alphaHash: Bt, combine: H.combine, mapUv: U && W(H.map.channel), aoMapUv: tt && W(H.aoMap.channel), lightMapUv: zt && W(H.lightMap.channel), bumpMapUv: ut && W(H.bumpMap.channel), normalMapUv: Pt && W(H.normalMap.channel), displacementMapUv: xt && W(H.displacementMap.channel), emissiveMapUv: Lt && W(H.emissiveMap.channel), metalnessMapUv: Mt && W(H.metalnessMap.channel), roughnessMapUv: Ht && W(H.roughnessMap.channel), anisotropyMapUv: pt && W(H.anisotropyMap.channel), clearcoatMapUv: ce && W(H.clearcoatMap.channel), clearcoatNormalMapUv: ee && W(H.clearcoatNormalMap.channel), clearcoatRoughnessMapUv: xe && W(H.clearcoatRoughnessMap.channel), iridescenceMapUv: me && W(H.iridescenceMap.channel), iridescenceThicknessMapUv: ne && W(H.iridescenceThicknessMap.channel), sheenColorMapUv: Le && W(H.sheenColorMap.channel), sheenRoughnessMapUv: wt && W(H.sheenRoughnessMap.channel), specularMapUv: it && W(H.specularMap.channel), specularColorMapUv: Rt && W(H.specularColorMap.channel), specularIntensityMapUv: jt && W(H.specularIntensityMap.channel), transmissionMapUv: Xt && W(H.transmissionMap.channel), thicknessMapUv: ot && W(H.thicknessMap.channel), alphaMapUv: te && W(H.alphaMap.channel), vertexTangents: !!q.attributes.tangent && (Pt || Gt), vertexColors: H.vertexColors, vertexAlphas: H.vertexColors === true && !!q.attributes.color && q.attributes.color.itemSize === 4, pointsUvs: w.isPoints === true && !!q.attributes.uv && (U || te), fog: !!Y, useFog: H.fog === true, fogExp2: !!Y && Y.isFogExp2, flatShading: H.flatShading === true && H.wireframe === false, sizeAttenuation: H.sizeAttenuation === true, logarithmicDepthBuffer: s, reversedDepthBuffer: et, skinning: w.isSkinnedMesh === true, morphTargets: q.morphAttributes.position !== void 0, morphNormals: q.morphAttributes.normal !== void 0, morphColors: q.morphAttributes.color !== void 0, morphTargetsCount: K, morphTextureStride: _, numDirLights: M.directional.length, numPointLights: M.point.length, numSpotLights: M.spot.length, numSpotLightMaps: M.spotLightMap.length, numRectAreaLights: M.rectArea.length, numHemiLights: M.hemi.length, numDirLightShadows: M.directionalShadowMap.length, numPointLightShadows: M.pointShadowMap.length, numSpotLightShadows: M.spotShadowMap.length, numSpotLightShadowsWithMaps: M.numSpotLightShadowsWithMaps, numLightProbes: M.numLightProbes, numClippingPlanes: z.numPlanes, numClipIntersection: z.numIntersection, dithering: H.dithering, shadowMapEnabled: t.shadowMap.enabled && p.length > 0, shadowMapType: t.shadowMap.type, toneMapping: ve, decodeVideoTexture: U && H.map.isVideoTexture === true && Uf.getTransfer(H.map.colorSpace) === dn, decodeVideoTextureEmissive: Lt && H.emissiveMap.isVideoTexture === true && Uf.getTransfer(H.emissiveMap.colorSpace) === dn, premultipliedAlpha: H.premultipliedAlpha, doubleSided: H.side === $3, flipSided: H.side === Y3, useDepthPacking: H.depthPacking >= 0, depthPacking: H.depthPacking || 0, index0AttributeName: H.index0AttributeName, extensionClipCullDistance: de && H.extensions.clipCullDistance === true && n.has("WEBGL_clip_cull_distance"), extensionMultiDraw: (de && H.extensions.multiDraw === true || I) && n.has("WEBGL_multi_draw"), rendererExtensionParallelShaderCompile: n.has("KHR_parallel_shader_compile"), customProgramCacheKey: H.customProgramCacheKey() };
    return Re.vertexUv1s = a.has(1), Re.vertexUv2s = a.has(2), Re.vertexUv3s = a.has(3), a.clear(), Re;
  }
  function y(H) {
    const M = [];
    if (H.shaderID ? M.push(H.shaderID) : (M.push(H.customVertexShaderID), M.push(H.customFragmentShaderID)), H.defines !== void 0) for (const p in H.defines) M.push(p), M.push(H.defines[p]);
    return H.isRawShaderMaterial === false && (m(M, H), j(M, H), M.push(t.outputColorSpace)), M.push(H.customProgramCacheKey), M.join();
  }
  function m(H, M) {
    H.push(M.precision), H.push(M.outputColorSpace), H.push(M.envMapMode), H.push(M.envMapCubeUVHeight), H.push(M.mapUv), H.push(M.alphaMapUv), H.push(M.lightMapUv), H.push(M.aoMapUv), H.push(M.bumpMapUv), H.push(M.normalMapUv), H.push(M.displacementMapUv), H.push(M.emissiveMapUv), H.push(M.metalnessMapUv), H.push(M.roughnessMapUv), H.push(M.anisotropyMapUv), H.push(M.clearcoatMapUv), H.push(M.clearcoatNormalMapUv), H.push(M.clearcoatRoughnessMapUv), H.push(M.iridescenceMapUv), H.push(M.iridescenceThicknessMapUv), H.push(M.sheenColorMapUv), H.push(M.sheenRoughnessMapUv), H.push(M.specularMapUv), H.push(M.specularColorMapUv), H.push(M.specularIntensityMapUv), H.push(M.transmissionMapUv), H.push(M.thicknessMapUv), H.push(M.combine), H.push(M.fogExp2), H.push(M.sizeAttenuation), H.push(M.morphTargetsCount), H.push(M.morphAttributeCount), H.push(M.numDirLights), H.push(M.numPointLights), H.push(M.numSpotLights), H.push(M.numSpotLightMaps), H.push(M.numHemiLights), H.push(M.numRectAreaLights), H.push(M.numDirLightShadows), H.push(M.numPointLightShadows), H.push(M.numSpotLightShadows), H.push(M.numSpotLightShadowsWithMaps), H.push(M.numLightProbes), H.push(M.shadowMapType), H.push(M.toneMapping), H.push(M.numClippingPlanes), H.push(M.numClipIntersection), H.push(M.depthPacking);
  }
  function j(H, M) {
    u.disableAll(), M.supportsVertexTextures && u.enable(0), M.instancing && u.enable(1), M.instancingColor && u.enable(2), M.instancingMorph && u.enable(3), M.matcap && u.enable(4), M.envMap && u.enable(5), M.normalMapObjectSpace && u.enable(6), M.normalMapTangentSpace && u.enable(7), M.clearcoat && u.enable(8), M.iridescence && u.enable(9), M.alphaTest && u.enable(10), M.vertexColors && u.enable(11), M.vertexAlphas && u.enable(12), M.vertexUv1s && u.enable(13), M.vertexUv2s && u.enable(14), M.vertexUv3s && u.enable(15), M.vertexTangents && u.enable(16), M.anisotropy && u.enable(17), M.alphaHash && u.enable(18), M.batching && u.enable(19), M.dispersion && u.enable(20), M.batchingColor && u.enable(21), M.gradientMap && u.enable(22), H.push(u.mask), u.disableAll(), M.fog && u.enable(0), M.useFog && u.enable(1), M.flatShading && u.enable(2), M.logarithmicDepthBuffer && u.enable(3), M.reversedDepthBuffer && u.enable(4), M.skinning && u.enable(5), M.morphTargets && u.enable(6), M.morphNormals && u.enable(7), M.morphColors && u.enable(8), M.premultipliedAlpha && u.enable(9), M.shadowMapEnabled && u.enable(10), M.doubleSided && u.enable(11), M.flipSided && u.enable(12), M.useDepthPacking && u.enable(13), M.dithering && u.enable(14), M.transmission && u.enable(15), M.sheen && u.enable(16), M.opaque && u.enable(17), M.pointsUvs && u.enable(18), M.decodeVideoTexture && u.enable(19), M.decodeVideoTextureEmissive && u.enable(20), M.alphaToCoverage && u.enable(21), H.push(u.mask);
  }
  function x(H) {
    const M = l[H.type];
    let p;
    if (M) {
      const T = Hz[M];
      p = cFt.clone(T.uniforms);
    } else p = H.uniforms;
    return p;
  }
  function L(H, M) {
    let p;
    for (let T = 0, w = P.length; T < w; T++) {
      const Y = P[T];
      if (Y.cacheKey === M) {
        p = Y, ++p.usedTimes;
        break;
      }
    }
    return p === void 0 && (p = new Ugt(t, M, H, r), P.push(p)), p;
  }
  function O(H) {
    if (--H.usedTimes === 0) {
      const M = P.indexOf(H);
      P[M] = P[P.length - 1], P.pop(), H.destroy();
    }
  }
  function N(H) {
    b.remove(H);
  }
  function i() {
    b.dispose();
  }
  return { getParameters: X, getProgramCacheKey: y, getUniforms: x, acquireProgram: L, releaseProgram: O, releaseShaderCache: N, programs: P, dispose: i };
}
function Agt() {
  let t = /* @__PURE__ */ new WeakMap();
  function e(z) {
    return t.has(z);
  }
  function f(z) {
    let u = t.get(z);
    return u === void 0 && (u = {}, t.set(z, u)), u;
  }
  function n(z) {
    t.delete(z);
  }
  function v(z, u, b) {
    t.get(z)[u] = b;
  }
  function r() {
    t = /* @__PURE__ */ new WeakMap();
  }
  return { has: e, get: f, remove: n, update: v, dispose: r };
}
function Qgt(t, e) {
  return t.groupOrder !== e.groupOrder ? t.groupOrder - e.groupOrder : t.renderOrder !== e.renderOrder ? t.renderOrder - e.renderOrder : t.material.id !== e.material.id ? t.material.id - e.material.id : t.z !== e.z ? t.z - e.z : t.id - e.id;
}
function hE(t, e) {
  return t.groupOrder !== e.groupOrder ? t.groupOrder - e.groupOrder : t.renderOrder !== e.renderOrder ? t.renderOrder - e.renderOrder : t.z !== e.z ? e.z - t.z : t.id - e.id;
}
function GE() {
  const t = [];
  let e = 0;
  const f = [], n = [], v = [];
  function r() {
    e = 0, f.length = 0, n.length = 0, v.length = 0;
  }
  function z(s, d, c, l, W, X) {
    let y = t[e];
    return y === void 0 ? (y = { id: s.id, object: s, geometry: d, material: c, groupOrder: l, renderOrder: s.renderOrder, z: W, group: X }, t[e] = y) : (y.id = s.id, y.object = s, y.geometry = d, y.material = c, y.groupOrder = l, y.renderOrder = s.renderOrder, y.z = W, y.group = X), e++, y;
  }
  function u(s, d, c, l, W, X) {
    const y = z(s, d, c, l, W, X);
    c.transmission > 0 ? n.push(y) : c.transparent === true ? v.push(y) : f.push(y);
  }
  function b(s, d, c, l, W, X) {
    const y = z(s, d, c, l, W, X);
    c.transmission > 0 ? n.unshift(y) : c.transparent === true ? v.unshift(y) : f.unshift(y);
  }
  function a(s, d) {
    f.length > 1 && f.sort(s || Qgt), n.length > 1 && n.sort(d || hE), v.length > 1 && v.sort(d || hE);
  }
  function P() {
    for (let s = e, d = t.length; s < d; s++) {
      const c = t[s];
      if (c.id === null) break;
      c.id = null, c.object = null, c.geometry = null, c.material = null, c.group = null;
    }
  }
  return { opaque: f, transmissive: n, transparent: v, init: r, push: u, unshift: b, finish: P, sort: a };
}
function _gt() {
  let t = /* @__PURE__ */ new WeakMap();
  function e(n, v) {
    const r = t.get(n);
    let z;
    return r === void 0 ? (z = new GE(), t.set(n, [z])) : v >= r.length ? (z = new GE(), r.push(z)) : z = r[v], z;
  }
  function f() {
    t = /* @__PURE__ */ new WeakMap();
  }
  return { get: e, dispose: f };
}
function $gt() {
  const t = {};
  return { get: function(e) {
    if (t[e.id] !== void 0) return t[e.id];
    let f;
    switch (e.type) {
      case "DirectionalLight":
        f = { direction: new ht(), color: new Nf() };
        break;
      case "SpotLight":
        f = { position: new ht(), direction: new ht(), color: new Nf(), distance: 0, coneCos: 0, penumbraCos: 0, decay: 0 };
        break;
      case "PointLight":
        f = { position: new ht(), color: new Nf(), distance: 0, decay: 0 };
        break;
      case "HemisphereLight":
        f = { direction: new ht(), skyColor: new Nf(), groundColor: new Nf() };
        break;
      case "RectAreaLight":
        f = { color: new Nf(), position: new ht(), halfWidth: new ht(), halfHeight: new ht() };
        break;
    }
    return t[e.id] = f, f;
  } };
}
function tSt() {
  const t = {};
  return { get: function(e) {
    if (t[e.id] !== void 0) return t[e.id];
    let f;
    switch (e.type) {
      case "DirectionalLight":
        f = { shadowIntensity: 1, shadowBias: 0, shadowNormalBias: 0, shadowRadius: 1, shadowMapSize: new Me() };
        break;
      case "SpotLight":
        f = { shadowIntensity: 1, shadowBias: 0, shadowNormalBias: 0, shadowRadius: 1, shadowMapSize: new Me() };
        break;
      case "PointLight":
        f = { shadowIntensity: 1, shadowBias: 0, shadowNormalBias: 0, shadowRadius: 1, shadowMapSize: new Me(), shadowCameraNear: 1, shadowCameraFar: 1e3 };
        break;
    }
    return t[e.id] = f, f;
  } };
}
let eSt = 0;
function fSt(t, e) {
  return (e.castShadow ? 2 : 0) - (t.castShadow ? 2 : 0) + (e.map ? 1 : 0) - (t.map ? 1 : 0);
}
function nSt(t) {
  const e = new $gt(), f = tSt(), n = { version: 0, hash: { directionalLength: -1, pointLength: -1, spotLength: -1, rectAreaLength: -1, hemiLength: -1, numDirectionalShadows: -1, numPointShadows: -1, numSpotShadows: -1, numSpotMaps: -1, numLightProbes: -1 }, ambient: [0, 0, 0], probe: [], directional: [], directionalShadow: [], directionalShadowMap: [], directionalShadowMatrix: [], spot: [], spotLightMap: [], spotShadow: [], spotShadowMap: [], spotLightMatrix: [], rectArea: [], rectAreaLTC1: null, rectAreaLTC2: null, point: [], pointShadow: [], pointShadowMap: [], pointShadowMatrix: [], hemi: [], numSpotLightShadowsWithMaps: 0, numLightProbes: 0 };
  for (let a = 0; a < 9; a++) n.probe.push(new ht());
  const v = new ht(), r = new cn(), z = new cn();
  function u(a) {
    let P = 0, s = 0, d = 0;
    for (let H = 0; H < 9; H++) n.probe[H].set(0, 0, 0);
    let c = 0, l = 0, W = 0, X = 0, y = 0, m = 0, j = 0, x = 0, L = 0, O = 0, N = 0;
    a.sort(fSt);
    for (let H = 0, M = a.length; H < M; H++) {
      const p = a[H], T = p.color, w = p.intensity, Y = p.distance, q = p.shadow && p.shadow.map ? p.shadow.map.texture : null;
      if (p.isAmbientLight) P += T.r * w, s += T.g * w, d += T.b * w;
      else if (p.isLightProbe) {
        for (let D = 0; D < 9; D++) n.probe[D].addScaledVector(p.sh.coefficients[D], w);
        N++;
      } else if (p.isDirectionalLight) {
        const D = e.get(p);
        if (D.color.copy(p.color).multiplyScalar(p.intensity), p.castShadow) {
          const V = p.shadow, G = f.get(p);
          G.shadowIntensity = V.intensity, G.shadowBias = V.bias, G.shadowNormalBias = V.normalBias, G.shadowRadius = V.radius, G.shadowMapSize = V.mapSize, n.directionalShadow[c] = G, n.directionalShadowMap[c] = q, n.directionalShadowMatrix[c] = p.shadow.matrix, m++;
        }
        n.directional[c] = D, c++;
      } else if (p.isSpotLight) {
        const D = e.get(p);
        D.position.setFromMatrixPosition(p.matrixWorld), D.color.copy(T).multiplyScalar(w), D.distance = Y, D.coneCos = Math.cos(p.angle), D.penumbraCos = Math.cos(p.angle * (1 - p.penumbra)), D.decay = p.decay, n.spot[W] = D;
        const V = p.shadow;
        if (p.map && (n.spotLightMap[L] = p.map, L++, V.updateMatrices(p), p.castShadow && O++), n.spotLightMatrix[W] = V.matrix, p.castShadow) {
          const G = f.get(p);
          G.shadowIntensity = V.intensity, G.shadowBias = V.bias, G.shadowNormalBias = V.normalBias, G.shadowRadius = V.radius, G.shadowMapSize = V.mapSize, n.spotShadow[W] = G, n.spotShadowMap[W] = q, x++;
        }
        W++;
      } else if (p.isRectAreaLight) {
        const D = e.get(p);
        D.color.copy(T).multiplyScalar(w), D.halfWidth.set(p.width * 0.5, 0, 0), D.halfHeight.set(0, p.height * 0.5, 0), n.rectArea[X] = D, X++;
      } else if (p.isPointLight) {
        const D = e.get(p);
        if (D.color.copy(p.color).multiplyScalar(p.intensity), D.distance = p.distance, D.decay = p.decay, p.castShadow) {
          const V = p.shadow, G = f.get(p);
          G.shadowIntensity = V.intensity, G.shadowBias = V.bias, G.shadowNormalBias = V.normalBias, G.shadowRadius = V.radius, G.shadowMapSize = V.mapSize, G.shadowCameraNear = V.camera.near, G.shadowCameraFar = V.camera.far, n.pointShadow[l] = G, n.pointShadowMap[l] = q, n.pointShadowMatrix[l] = p.shadow.matrix, j++;
        }
        n.point[l] = D, l++;
      } else if (p.isHemisphereLight) {
        const D = e.get(p);
        D.skyColor.copy(p.color).multiplyScalar(w), D.groundColor.copy(p.groundColor).multiplyScalar(w), n.hemi[y] = D, y++;
      }
    }
    X > 0 && (t.has("OES_texture_float_linear") === true ? (n.rectAreaLTC1 = Ue.LTC_FLOAT_1, n.rectAreaLTC2 = Ue.LTC_FLOAT_2) : (n.rectAreaLTC1 = Ue.LTC_HALF_1, n.rectAreaLTC2 = Ue.LTC_HALF_2)), n.ambient[0] = P, n.ambient[1] = s, n.ambient[2] = d;
    const i = n.hash;
    (i.directionalLength !== c || i.pointLength !== l || i.spotLength !== W || i.rectAreaLength !== X || i.hemiLength !== y || i.numDirectionalShadows !== m || i.numPointShadows !== j || i.numSpotShadows !== x || i.numSpotMaps !== L || i.numLightProbes !== N) && (n.directional.length = c, n.spot.length = W, n.rectArea.length = X, n.point.length = l, n.hemi.length = y, n.directionalShadow.length = m, n.directionalShadowMap.length = m, n.pointShadow.length = j, n.pointShadowMap.length = j, n.spotShadow.length = x, n.spotShadowMap.length = x, n.directionalShadowMatrix.length = m, n.pointShadowMatrix.length = j, n.spotLightMatrix.length = x + L - O, n.spotLightMap.length = L, n.numSpotLightShadowsWithMaps = O, n.numLightProbes = N, i.directionalLength = c, i.pointLength = l, i.spotLength = W, i.rectAreaLength = X, i.hemiLength = y, i.numDirectionalShadows = m, i.numPointShadows = j, i.numSpotShadows = x, i.numSpotMaps = L, i.numLightProbes = N, n.version = eSt++);
  }
  function b(a, P) {
    let s = 0, d = 0, c = 0, l = 0, W = 0;
    const X = P.matrixWorldInverse;
    for (let y = 0, m = a.length; y < m; y++) {
      const j = a[y];
      if (j.isDirectionalLight) {
        const x = n.directional[s];
        x.direction.setFromMatrixPosition(j.matrixWorld), v.setFromMatrixPosition(j.target.matrixWorld), x.direction.sub(v), x.direction.transformDirection(X), s++;
      } else if (j.isSpotLight) {
        const x = n.spot[c];
        x.position.setFromMatrixPosition(j.matrixWorld), x.position.applyMatrix4(X), x.direction.setFromMatrixPosition(j.matrixWorld), v.setFromMatrixPosition(j.target.matrixWorld), x.direction.sub(v), x.direction.transformDirection(X), c++;
      } else if (j.isRectAreaLight) {
        const x = n.rectArea[l];
        x.position.setFromMatrixPosition(j.matrixWorld), x.position.applyMatrix4(X), z.identity(), r.copy(j.matrixWorld), r.premultiply(X), z.extractRotation(r), x.halfWidth.set(j.width * 0.5, 0, 0), x.halfHeight.set(0, j.height * 0.5, 0), x.halfWidth.applyMatrix4(z), x.halfHeight.applyMatrix4(z), l++;
      } else if (j.isPointLight) {
        const x = n.point[d];
        x.position.setFromMatrixPosition(j.matrixWorld), x.position.applyMatrix4(X), d++;
      } else if (j.isHemisphereLight) {
        const x = n.hemi[W];
        x.direction.setFromMatrixPosition(j.matrixWorld), x.direction.transformDirection(X), W++;
      }
    }
  }
  return { setup: u, setupView: b, state: n };
}
function VE(t) {
  const e = new nSt(t), f = [], n = [];
  function v(P) {
    a.camera = P, f.length = 0, n.length = 0;
  }
  function r(P) {
    f.push(P);
  }
  function z(P) {
    n.push(P);
  }
  function u() {
    e.setup(f);
  }
  function b(P) {
    e.setupView(f, P);
  }
  const a = { lightsArray: f, shadowsArray: n, camera: null, lights: e, transmissionRenderTarget: {} };
  return { init: v, state: a, setupLights: u, setupLightsView: b, pushLight: r, pushShadow: z };
}
function vSt(t) {
  let e = /* @__PURE__ */ new WeakMap();
  function f(v, r = 0) {
    const z = e.get(v);
    let u;
    return z === void 0 ? (u = new VE(t), e.set(v, [u])) : r >= z.length ? (u = new VE(t), z.push(u)) : u = z[r], u;
  }
  function n() {
    e = /* @__PURE__ */ new WeakMap();
  }
  return { get: f, dispose: n };
}
const rSt = `void main() {
	gl_Position = vec4( position, 1.0 );
}`;
const zSt = `uniform sampler2D shadow_pass;
uniform vec2 resolution;
uniform float radius;
#include <packing>
void main() {
	const float samples = float( VSM_SAMPLES );
	float mean = 0.0;
	float squared_mean = 0.0;
	float uvStride = samples <= 1.0 ? 0.0 : 2.0 / ( samples - 1.0 );
	float uvStart = samples <= 1.0 ? 0.0 : - 1.0;
	for ( float i = 0.0; i < samples; i ++ ) {
		float uvOffset = uvStart + i * uvStride;
		#ifdef HORIZONTAL_PASS
			vec2 distribution = unpackRGBATo2Half( texture2D( shadow_pass, ( gl_FragCoord.xy + vec2( uvOffset, 0.0 ) * radius ) / resolution ) );
			mean += distribution.x;
			squared_mean += distribution.y * distribution.y + distribution.x * distribution.x;
		#else
			float depth = unpackRGBAToDepth( texture2D( shadow_pass, ( gl_FragCoord.xy + vec2( 0.0, uvOffset ) * radius ) / resolution ) );
			mean += depth;
			squared_mean += depth * depth;
		#endif
	}
	mean = mean / samples;
	squared_mean = squared_mean / samples;
	float std_dev = sqrt( squared_mean - mean * mean );
	gl_FragColor = pack2HalfToRGBA( vec2( mean, std_dev ) );
}`;
function uSt(t, e, f) {
  let n = new MM();
  const v = new Me(), r = new Me(), z = new Un(), u = new uRt({ depthPacking: Lqt }), b = new bRt(), a = {}, P = f.maxTextureSize, s = { [M5]: Y3, [Y3]: M5, [$3]: $3 }, d = new Y5({ defines: { VSM_SAMPLES: 8 }, uniforms: { shadow_pass: { value: null }, resolution: { value: new Me() }, radius: { value: 4 } }, vertexShader: rSt, fragmentShader: zSt }), c = d.clone();
  c.defines.HORIZONTAL_PASS = 1;
  const l = new bv();
  l.setAttribute("position", new ov(new Float32Array([-1, -1, 0.5, 3, -1, 0.5, -1, 3, 0.5]), 3));
  const W = new Zn(l, d), X = this;
  this.enabled = false, this.autoUpdate = true, this.needsUpdate = false, this.type = GI;
  let y = this.type;
  this.render = function(O, N, i) {
    if (X.enabled === false || X.autoUpdate === false && X.needsUpdate === false || O.length === 0) return;
    const H = t.getRenderTarget(), M = t.getActiveCubeFace(), p = t.getActiveMipmapLevel(), T = t.state;
    T.setBlending(i5), T.buffers.depth.getReversed() === true ? T.buffers.color.setClear(0, 0, 0, 0) : T.buffers.color.setClear(1, 1, 1, 1), T.buffers.depth.setTest(true), T.setScissorTest(false);
    const w = y !== s9 && this.type === s9, Y = y === s9 && this.type !== s9;
    for (let q = 0, D = O.length; q < D; q++) {
      const V = O[q], G = V.shadow;
      if (G === void 0) {
        console.warn("THREE.WebGLShadowMap:", V, "has no shadow.");
        continue;
      }
      if (G.autoUpdate === false && G.needsUpdate === false) continue;
      v.copy(G.mapSize);
      const R = G.getFrameExtents();
      if (v.multiply(R), r.copy(G.mapSize), (v.x > P || v.y > P) && (v.x > P && (r.x = Math.floor(P / R.x), v.x = r.x * R.x, G.mapSize.x = r.x), v.y > P && (r.y = Math.floor(P / R.y), v.y = r.y * R.y, G.mapSize.y = r.y)), G.map === null || w === true || Y === true) {
        const K = this.type !== s9 ? { minFilter: tr, magFilter: tr } : {};
        G.map !== null && G.map.dispose(), G.map = new ib(v.x, v.y, K), G.map.texture.name = V.name + ".shadowMap", G.camera.updateProjectionMatrix();
      }
      t.setRenderTarget(G.map), t.clear();
      const k = G.getViewportCount();
      for (let K = 0; K < k; K++) {
        const _ = G.getViewport(K);
        z.set(r.x * _.x, r.y * _.y, r.x * _.z, r.y * _.w), T.viewport(z), G.updateMatrices(V, K), n = G.getFrustum(), x(N, i, G.camera, V, this.type);
      }
      G.isPointLightShadow !== true && this.type === s9 && m(G, i), G.needsUpdate = false;
    }
    y = this.type, X.needsUpdate = false, t.setRenderTarget(H, M, p);
  };
  function m(O, N) {
    const i = e.update(W);
    d.defines.VSM_SAMPLES !== O.blurSamples && (d.defines.VSM_SAMPLES = O.blurSamples, c.defines.VSM_SAMPLES = O.blurSamples, d.needsUpdate = true, c.needsUpdate = true), O.mapPass === null && (O.mapPass = new ib(v.x, v.y)), d.uniforms.shadow_pass.value = O.map.texture, d.uniforms.resolution.value = O.mapSize, d.uniforms.radius.value = O.radius, t.setRenderTarget(O.mapPass), t.clear(), t.renderBufferDirect(N, null, i, d, W, null), c.uniforms.shadow_pass.value = O.mapPass.texture, c.uniforms.resolution.value = O.mapSize, c.uniforms.radius.value = O.radius, t.setRenderTarget(O.map), t.clear(), t.renderBufferDirect(N, null, i, c, W, null);
  }
  function j(O, N, i, H) {
    let M = null;
    const p = i.isPointLight === true ? O.customDistanceMaterial : O.customDepthMaterial;
    if (p !== void 0) M = p;
    else if (M = i.isPointLight === true ? b : u, t.localClippingEnabled && N.clipShadows === true && Array.isArray(N.clippingPlanes) && N.clippingPlanes.length !== 0 || N.displacementMap && N.displacementScale !== 0 || N.alphaMap && N.alphaTest > 0 || N.map && N.alphaTest > 0 || N.alphaToCoverage === true) {
      const T = M.uuid, w = N.uuid;
      let Y = a[T];
      Y === void 0 && (Y = {}, a[T] = Y);
      let q = Y[w];
      q === void 0 && (q = M.clone(), Y[w] = q, N.addEventListener("dispose", L)), M = q;
    }
    if (M.visible = N.visible, M.wireframe = N.wireframe, H === s9 ? M.side = N.shadowSide !== null ? N.shadowSide : N.side : M.side = N.shadowSide !== null ? N.shadowSide : s[N.side], M.alphaMap = N.alphaMap, M.alphaTest = N.alphaToCoverage === true ? 0.5 : N.alphaTest, M.map = N.map, M.clipShadows = N.clipShadows, M.clippingPlanes = N.clippingPlanes, M.clipIntersection = N.clipIntersection, M.displacementMap = N.displacementMap, M.displacementScale = N.displacementScale, M.displacementBias = N.displacementBias, M.wireframeLinewidth = N.wireframeLinewidth, M.linewidth = N.linewidth, i.isPointLight === true && M.isMeshDistanceMaterial === true) {
      const T = t.properties.get(M);
      T.light = i;
    }
    return M;
  }
  function x(O, N, i, H, M) {
    if (O.visible === false) return;
    if (O.layers.test(N.layers) && (O.isMesh || O.isLine || O.isPoints) && (O.castShadow || O.receiveShadow && M === s9) && (!O.frustumCulled || n.intersectsObject(O))) {
      O.modelViewMatrix.multiplyMatrices(i.matrixWorldInverse, O.matrixWorld);
      const w = e.update(O), Y = O.material;
      if (Array.isArray(Y)) {
        const q = w.groups;
        for (let D = 0, V = q.length; D < V; D++) {
          const G = q[D], R = Y[G.materialIndex];
          if (R && R.visible) {
            const k = j(O, R, H, M);
            O.onBeforeShadow(t, O, N, i, w, k, G), t.renderBufferDirect(i, null, w, k, O, G), O.onAfterShadow(t, O, N, i, w, k, G);
          }
        }
      } else if (Y.visible) {
        const q = j(O, Y, H, M);
        O.onBeforeShadow(t, O, N, i, w, q, null), t.renderBufferDirect(i, null, w, q, O, null), O.onAfterShadow(t, O, N, i, w, q, null);
      }
    }
    const T = O.children;
    for (let w = 0, Y = T.length; w < Y; w++) x(T[w], N, i, H, M);
  }
  function L(O) {
    O.target.removeEventListener("dispose", L);
    for (const i in a) {
      const H = a[i], M = O.target.uuid;
      M in H && (H[M].dispose(), delete H[M]);
    }
  }
}
const bSt = { [uN]: bN, [aN]: dN, [PN]: cN, [Ja]: sN, [bN]: uN, [dN]: aN, [cN]: PN, [sN]: Ja };
function aSt(t, e) {
  function f() {
    let Xt = false;
    const ot = new Un();
    let Ut = null;
    const te = new Un(0, 0, 0, 0);
    return { setMask: function(It) {
      Ut !== It && !Xt && (t.colorMask(It, It, It, It), Ut = It);
    }, setLocked: function(It) {
      Xt = It;
    }, setClear: function(It, Bt, de, ve, Re) {
      Re === true && (It *= ve, Bt *= ve, de *= ve), ot.set(It, Bt, de, ve), te.equals(ot) === false && (t.clearColor(It, Bt, de, ve), te.copy(ot));
    }, reset: function() {
      Xt = false, Ut = null, te.set(-1, 0, 0, 0);
    } };
  }
  function n() {
    let Xt = false, ot = false, Ut = null, te = null, It = null;
    return { setReversed: function(Bt) {
      if (ot !== Bt) {
        const de = e.get("EXT_clip_control");
        Bt ? de.clipControlEXT(de.LOWER_LEFT_EXT, de.ZERO_TO_ONE_EXT) : de.clipControlEXT(de.LOWER_LEFT_EXT, de.NEGATIVE_ONE_TO_ONE_EXT), ot = Bt;
        const ve = It;
        It = null, this.setClear(ve);
      }
    }, getReversed: function() {
      return ot;
    }, setTest: function(Bt) {
      Bt ? B(t.DEPTH_TEST) : et(t.DEPTH_TEST);
    }, setMask: function(Bt) {
      Ut !== Bt && !Xt && (t.depthMask(Bt), Ut = Bt);
    }, setFunc: function(Bt) {
      if (ot && (Bt = bSt[Bt]), te !== Bt) {
        switch (Bt) {
          case uN:
            t.depthFunc(t.NEVER);
            break;
          case bN:
            t.depthFunc(t.ALWAYS);
            break;
          case aN:
            t.depthFunc(t.LESS);
            break;
          case Ja:
            t.depthFunc(t.LEQUAL);
            break;
          case PN:
            t.depthFunc(t.EQUAL);
            break;
          case sN:
            t.depthFunc(t.GEQUAL);
            break;
          case dN:
            t.depthFunc(t.GREATER);
            break;
          case cN:
            t.depthFunc(t.NOTEQUAL);
            break;
          default:
            t.depthFunc(t.LEQUAL);
        }
        te = Bt;
      }
    }, setLocked: function(Bt) {
      Xt = Bt;
    }, setClear: function(Bt) {
      It !== Bt && (ot && (Bt = 1 - Bt), t.clearDepth(Bt), It = Bt);
    }, reset: function() {
      Xt = false, Ut = null, te = null, It = null, ot = false;
    } };
  }
  function v() {
    let Xt = false, ot = null, Ut = null, te = null, It = null, Bt = null, de = null, ve = null, Re = null;
    return { setTest: function(Qt) {
      Xt || (Qt ? B(t.STENCIL_TEST) : et(t.STENCIL_TEST));
    }, setMask: function(Qt) {
      ot !== Qt && !Xt && (t.stencilMask(Qt), ot = Qt);
    }, setFunc: function(Qt, be, Oe) {
      (Ut !== Qt || te !== be || It !== Oe) && (t.stencilFunc(Qt, be, Oe), Ut = Qt, te = be, It = Oe);
    }, setOp: function(Qt, be, Oe) {
      (Bt !== Qt || de !== be || ve !== Oe) && (t.stencilOp(Qt, be, Oe), Bt = Qt, de = be, ve = Oe);
    }, setLocked: function(Qt) {
      Xt = Qt;
    }, setClear: function(Qt) {
      Re !== Qt && (t.clearStencil(Qt), Re = Qt);
    }, reset: function() {
      Xt = false, ot = null, Ut = null, te = null, It = null, Bt = null, de = null, ve = null, Re = null;
    } };
  }
  const r = new f(), z = new n(), u = new v(), b = /* @__PURE__ */ new WeakMap(), a = /* @__PURE__ */ new WeakMap();
  let P = {}, s = {}, d = /* @__PURE__ */ new WeakMap(), c = [], l = null, W = false, X = null, y = null, m = null, j = null, x = null, L = null, O = null, N = new Nf(0, 0, 0), i = 0, H = false, M = null, p = null, T = null, w = null, Y = null;
  const q = t.getParameter(t.MAX_COMBINED_TEXTURE_IMAGE_UNITS);
  let D = false, V = 0;
  const G = t.getParameter(t.VERSION);
  G.indexOf("WebGL") !== -1 ? (V = parseFloat(/^WebGL (\d)/.exec(G)[1]), D = V >= 1) : G.indexOf("OpenGL ES") !== -1 && (V = parseFloat(/^OpenGL ES (\d)/.exec(G)[1]), D = V >= 2);
  let R = null, k = {};
  const K = t.getParameter(t.SCISSOR_BOX), _ = t.getParameter(t.VIEWPORT), vt = new Un().fromArray(K), rt = new Un().fromArray(_);
  function A(Xt, ot, Ut, te) {
    const It = new Uint8Array(4), Bt = t.createTexture();
    t.bindTexture(Xt, Bt), t.texParameteri(Xt, t.TEXTURE_MIN_FILTER, t.NEAREST), t.texParameteri(Xt, t.TEXTURE_MAG_FILTER, t.NEAREST);
    for (let de = 0; de < Ut; de++) Xt === t.TEXTURE_3D || Xt === t.TEXTURE_2D_ARRAY ? t.texImage3D(ot, 0, t.RGBA, 1, 1, te, 0, t.RGBA, t.UNSIGNED_BYTE, It) : t.texImage2D(ot + de, 0, t.RGBA, 1, 1, 0, t.RGBA, t.UNSIGNED_BYTE, It);
    return Bt;
  }
  const Q = {};
  Q[t.TEXTURE_2D] = A(t.TEXTURE_2D, t.TEXTURE_2D, 1), Q[t.TEXTURE_CUBE_MAP] = A(t.TEXTURE_CUBE_MAP, t.TEXTURE_CUBE_MAP_POSITIVE_X, 6), Q[t.TEXTURE_2D_ARRAY] = A(t.TEXTURE_2D_ARRAY, t.TEXTURE_2D_ARRAY, 1, 1), Q[t.TEXTURE_3D] = A(t.TEXTURE_3D, t.TEXTURE_3D, 1, 1), r.setClear(0, 0, 0, 1), z.setClear(1), u.setClear(0), B(t.DEPTH_TEST), z.setFunc(Ja), ut(false), Pt(hI), B(t.CULL_FACE), tt(i5);
  function B(Xt) {
    P[Xt] !== true && (t.enable(Xt), P[Xt] = true);
  }
  function et(Xt) {
    P[Xt] !== false && (t.disable(Xt), P[Xt] = false);
  }
  function nt(Xt, ot) {
    return s[Xt] !== ot ? (t.bindFramebuffer(Xt, ot), s[Xt] = ot, Xt === t.DRAW_FRAMEBUFFER && (s[t.FRAMEBUFFER] = ot), Xt === t.FRAMEBUFFER && (s[t.DRAW_FRAMEBUFFER] = ot), true) : false;
  }
  function I(Xt, ot) {
    let Ut = c, te = false;
    if (Xt) {
      Ut = d.get(ot), Ut === void 0 && (Ut = [], d.set(ot, Ut));
      const It = Xt.textures;
      if (Ut.length !== It.length || Ut[0] !== t.COLOR_ATTACHMENT0) {
        for (let Bt = 0, de = It.length; Bt < de; Bt++) Ut[Bt] = t.COLOR_ATTACHMENT0 + Bt;
        Ut.length = It.length, te = true;
      }
    } else Ut[0] !== t.BACK && (Ut[0] = t.BACK, te = true);
    te && t.drawBuffers(Ut);
  }
  function U(Xt) {
    return l !== Xt ? (t.useProgram(Xt), l = Xt, true) : false;
  }
  const J = { [xb]: t.FUNC_ADD, [CDt]: t.FUNC_SUBTRACT, [ADt]: t.FUNC_REVERSE_SUBTRACT };
  J[QDt] = t.MIN, J[_Dt] = t.MAX;
  const g = { [$Dt]: t.ZERO, [tqt]: t.ONE, [eqt]: t.SRC_COLOR, [rN]: t.SRC_ALPHA, [uqt]: t.SRC_ALPHA_SATURATE, [rqt]: t.DST_COLOR, [nqt]: t.DST_ALPHA, [fqt]: t.ONE_MINUS_SRC_COLOR, [zN]: t.ONE_MINUS_SRC_ALPHA, [zqt]: t.ONE_MINUS_DST_COLOR, [vqt]: t.ONE_MINUS_DST_ALPHA, [bqt]: t.CONSTANT_COLOR, [aqt]: t.ONE_MINUS_CONSTANT_COLOR, [Pqt]: t.CONSTANT_ALPHA, [sqt]: t.ONE_MINUS_CONSTANT_ALPHA };
  function tt(Xt, ot, Ut, te, It, Bt, de, ve, Re, Qt) {
    if (Xt === i5) {
      W === true && (et(t.BLEND), W = false);
      return;
    }
    if (W === false && (B(t.BLEND), W = true), Xt !== EDt) {
      if (Xt !== X || Qt !== H) {
        if ((y !== xb || x !== xb) && (t.blendEquation(t.FUNC_ADD), y = xb, x = xb), Qt) switch (Xt) {
          case ka:
            t.blendFuncSeparate(t.ONE, t.ONE_MINUS_SRC_ALPHA, t.ONE, t.ONE_MINUS_SRC_ALPHA);
            break;
          case YI:
            t.blendFunc(t.ONE, t.ONE);
            break;
          case DI:
            t.blendFuncSeparate(t.ZERO, t.ONE_MINUS_SRC_COLOR, t.ZERO, t.ONE);
            break;
          case qI:
            t.blendFuncSeparate(t.DST_COLOR, t.ONE_MINUS_SRC_ALPHA, t.ZERO, t.ONE);
            break;
          default:
            console.error("THREE.WebGLState: Invalid blending: ", Xt);
            break;
        }
        else switch (Xt) {
          case ka:
            t.blendFuncSeparate(t.SRC_ALPHA, t.ONE_MINUS_SRC_ALPHA, t.ONE, t.ONE_MINUS_SRC_ALPHA);
            break;
          case YI:
            t.blendFuncSeparate(t.SRC_ALPHA, t.ONE, t.ONE, t.ONE);
            break;
          case DI:
            console.error("THREE.WebGLState: SubtractiveBlending requires material.premultipliedAlpha = true");
            break;
          case qI:
            console.error("THREE.WebGLState: MultiplyBlending requires material.premultipliedAlpha = true");
            break;
          default:
            console.error("THREE.WebGLState: Invalid blending: ", Xt);
            break;
        }
        m = null, j = null, L = null, O = null, N.set(0, 0, 0), i = 0, X = Xt, H = Qt;
      }
      return;
    }
    It = It || ot, Bt = Bt || Ut, de = de || te, (ot !== y || It !== x) && (t.blendEquationSeparate(J[ot], J[It]), y = ot, x = It), (Ut !== m || te !== j || Bt !== L || de !== O) && (t.blendFuncSeparate(g[Ut], g[te], g[Bt], g[de]), m = Ut, j = te, L = Bt, O = de), (ve.equals(N) === false || Re !== i) && (t.blendColor(ve.r, ve.g, ve.b, Re), N.copy(ve), i = Re), X = Xt, H = false;
  }
  function zt(Xt, ot) {
    Xt.side === $3 ? et(t.CULL_FACE) : B(t.CULL_FACE);
    let Ut = Xt.side === Y3;
    ot && (Ut = !Ut), ut(Ut), Xt.blending === ka && Xt.transparent === false ? tt(i5) : tt(Xt.blending, Xt.blendEquation, Xt.blendSrc, Xt.blendDst, Xt.blendEquationAlpha, Xt.blendSrcAlpha, Xt.blendDstAlpha, Xt.blendColor, Xt.blendAlpha, Xt.premultipliedAlpha), z.setFunc(Xt.depthFunc), z.setTest(Xt.depthTest), z.setMask(Xt.depthWrite), r.setMask(Xt.colorWrite);
    const te = Xt.stencilWrite;
    u.setTest(te), te && (u.setMask(Xt.stencilWriteMask), u.setFunc(Xt.stencilFunc, Xt.stencilRef, Xt.stencilFuncMask), u.setOp(Xt.stencilFail, Xt.stencilZFail, Xt.stencilZPass)), Lt(Xt.polygonOffset, Xt.polygonOffsetFactor, Xt.polygonOffsetUnits), Xt.alphaToCoverage === true ? B(t.SAMPLE_ALPHA_TO_COVERAGE) : et(t.SAMPLE_ALPHA_TO_COVERAGE);
  }
  function ut(Xt) {
    M !== Xt && (Xt ? t.frontFace(t.CW) : t.frontFace(t.CCW), M = Xt);
  }
  function Pt(Xt) {
    Xt !== IDt ? (B(t.CULL_FACE), Xt !== p && (Xt === hI ? t.cullFace(t.BACK) : Xt === KDt ? t.cullFace(t.FRONT) : t.cullFace(t.FRONT_AND_BACK))) : et(t.CULL_FACE), p = Xt;
  }
  function xt(Xt) {
    Xt !== T && (D && t.lineWidth(Xt), T = Xt);
  }
  function Lt(Xt, ot, Ut) {
    Xt ? (B(t.POLYGON_OFFSET_FILL), (w !== ot || Y !== Ut) && (t.polygonOffset(ot, Ut), w = ot, Y = Ut)) : et(t.POLYGON_OFFSET_FILL);
  }
  function Mt(Xt) {
    Xt ? B(t.SCISSOR_TEST) : et(t.SCISSOR_TEST);
  }
  function Ht(Xt) {
    Xt === void 0 && (Xt = t.TEXTURE0 + q - 1), R !== Xt && (t.activeTexture(Xt), R = Xt);
  }
  function Gt(Xt, ot, Ut) {
    Ut === void 0 && (R === null ? Ut = t.TEXTURE0 + q - 1 : Ut = R);
    let te = k[Ut];
    te === void 0 && (te = { type: void 0, texture: void 0 }, k[Ut] = te), (te.type !== Xt || te.texture !== ot) && (R !== Ut && (t.activeTexture(Ut), R = Ut), t.bindTexture(Xt, ot || Q[Xt]), te.type = Xt, te.texture = ot);
  }
  function ft() {
    const Xt = k[R];
    Xt !== void 0 && Xt.type !== void 0 && (t.bindTexture(Xt.type, null), Xt.type = void 0, Xt.texture = void 0);
  }
  function $() {
    try {
      t.compressedTexImage2D(...arguments);
    } catch (Xt) {
      console.error("THREE.WebGLState:", Xt);
    }
  }
  function yt() {
    try {
      t.compressedTexImage3D(...arguments);
    } catch (Xt) {
      console.error("THREE.WebGLState:", Xt);
    }
  }
  function Tt() {
    try {
      t.texSubImage2D(...arguments);
    } catch (Xt) {
      console.error("THREE.WebGLState:", Xt);
    }
  }
  function Dt() {
    try {
      t.texSubImage3D(...arguments);
    } catch (Xt) {
      console.error("THREE.WebGLState:", Xt);
    }
  }
  function pt() {
    try {
      t.compressedTexSubImage2D(...arguments);
    } catch (Xt) {
      console.error("THREE.WebGLState:", Xt);
    }
  }
  function ce() {
    try {
      t.compressedTexSubImage3D(...arguments);
    } catch (Xt) {
      console.error("THREE.WebGLState:", Xt);
    }
  }
  function ee() {
    try {
      t.texStorage2D(...arguments);
    } catch (Xt) {
      console.error("THREE.WebGLState:", Xt);
    }
  }
  function xe() {
    try {
      t.texStorage3D(...arguments);
    } catch (Xt) {
      console.error("THREE.WebGLState:", Xt);
    }
  }
  function me() {
    try {
      t.texImage2D(...arguments);
    } catch (Xt) {
      console.error("THREE.WebGLState:", Xt);
    }
  }
  function ne() {
    try {
      t.texImage3D(...arguments);
    } catch (Xt) {
      console.error("THREE.WebGLState:", Xt);
    }
  }
  function Le(Xt) {
    vt.equals(Xt) === false && (t.scissor(Xt.x, Xt.y, Xt.z, Xt.w), vt.copy(Xt));
  }
  function wt(Xt) {
    rt.equals(Xt) === false && (t.viewport(Xt.x, Xt.y, Xt.z, Xt.w), rt.copy(Xt));
  }
  function it(Xt, ot) {
    let Ut = a.get(ot);
    Ut === void 0 && (Ut = /* @__PURE__ */ new WeakMap(), a.set(ot, Ut));
    let te = Ut.get(Xt);
    te === void 0 && (te = t.getUniformBlockIndex(ot, Xt.name), Ut.set(Xt, te));
  }
  function Rt(Xt, ot) {
    const te = a.get(ot).get(Xt);
    b.get(ot) !== te && (t.uniformBlockBinding(ot, te, Xt.__bindingPointIndex), b.set(ot, te));
  }
  function jt() {
    t.disable(t.BLEND), t.disable(t.CULL_FACE), t.disable(t.DEPTH_TEST), t.disable(t.POLYGON_OFFSET_FILL), t.disable(t.SCISSOR_TEST), t.disable(t.STENCIL_TEST), t.disable(t.SAMPLE_ALPHA_TO_COVERAGE), t.blendEquation(t.FUNC_ADD), t.blendFunc(t.ONE, t.ZERO), t.blendFuncSeparate(t.ONE, t.ZERO, t.ONE, t.ZERO), t.blendColor(0, 0, 0, 0), t.colorMask(true, true, true, true), t.clearColor(0, 0, 0, 0), t.depthMask(true), t.depthFunc(t.LESS), z.setReversed(false), t.clearDepth(1), t.stencilMask(4294967295), t.stencilFunc(t.ALWAYS, 0, 4294967295), t.stencilOp(t.KEEP, t.KEEP, t.KEEP), t.clearStencil(0), t.cullFace(t.BACK), t.frontFace(t.CCW), t.polygonOffset(0, 0), t.activeTexture(t.TEXTURE0), t.bindFramebuffer(t.FRAMEBUFFER, null), t.bindFramebuffer(t.DRAW_FRAMEBUFFER, null), t.bindFramebuffer(t.READ_FRAMEBUFFER, null), t.useProgram(null), t.lineWidth(1), t.scissor(0, 0, t.canvas.width, t.canvas.height), t.viewport(0, 0, t.canvas.width, t.canvas.height), P = {}, R = null, k = {}, s = {}, d = /* @__PURE__ */ new WeakMap(), c = [], l = null, W = false, X = null, y = null, m = null, j = null, x = null, L = null, O = null, N = new Nf(0, 0, 0), i = 0, H = false, M = null, p = null, T = null, w = null, Y = null, vt.set(0, 0, t.canvas.width, t.canvas.height), rt.set(0, 0, t.canvas.width, t.canvas.height), r.reset(), z.reset(), u.reset();
  }
  return { buffers: { color: r, depth: z, stencil: u }, enable: B, disable: et, bindFramebuffer: nt, drawBuffers: I, useProgram: U, setBlending: tt, setMaterial: zt, setFlipSided: ut, setCullFace: Pt, setLineWidth: xt, setPolygonOffset: Lt, setScissorTest: Mt, activeTexture: Ht, bindTexture: Gt, unbindTexture: ft, compressedTexImage2D: $, compressedTexImage3D: yt, texImage2D: me, texImage3D: ne, updateUBOMapping: it, uniformBlockBinding: Rt, texStorage2D: ee, texStorage3D: xe, texSubImage2D: Tt, texSubImage3D: Dt, compressedTexSubImage2D: pt, compressedTexSubImage3D: ce, scissor: Le, viewport: wt, reset: jt };
}
function PSt(t, e, f, n, v, r, z) {
  const u = e.has("WEBGL_multisampled_render_to_texture") ? e.get("WEBGL_multisampled_render_to_texture") : null, b = typeof navigator > "u" ? false : /OculusBrowser/g.test(navigator.userAgent), a = new Me(), P = /* @__PURE__ */ new WeakMap();
  let s;
  const d = /* @__PURE__ */ new WeakMap();
  let c = false;
  try {
    c = typeof OffscreenCanvas < "u" && new OffscreenCanvas(1, 1).getContext("2d") !== null;
  } catch {
  }
  function l(ft, $) {
    return c ? new OffscreenCanvas(ft, $) : P6("canvas");
  }
  function W(ft, $, yt) {
    let Tt = 1;
    const Dt = Gt(ft);
    if ((Dt.width > yt || Dt.height > yt) && (Tt = yt / Math.max(Dt.width, Dt.height)), Tt < 1) if (typeof HTMLImageElement < "u" && ft instanceof HTMLImageElement || typeof HTMLCanvasElement < "u" && ft instanceof HTMLCanvasElement || typeof ImageBitmap < "u" && ft instanceof ImageBitmap || typeof VideoFrame < "u" && ft instanceof VideoFrame) {
      const pt = Math.floor(Tt * Dt.width), ce = Math.floor(Tt * Dt.height);
      s === void 0 && (s = l(pt, ce));
      const ee = $ ? l(pt, ce) : s;
      return ee.width = pt, ee.height = ce, ee.getContext("2d").drawImage(ft, 0, 0, pt, ce), console.warn("THREE.WebGLRenderer: Texture has been resized from (" + Dt.width + "x" + Dt.height + ") to (" + pt + "x" + ce + ")."), ee;
    } else return "data" in ft && console.warn("THREE.WebGLRenderer: Image in DataTexture is too big (" + Dt.width + "x" + Dt.height + ")."), ft;
    return ft;
  }
  function X(ft) {
    return ft.generateMipmaps;
  }
  function y(ft) {
    t.generateMipmap(ft);
  }
  function m(ft) {
    return ft.isWebGLCubeRenderTarget ? t.TEXTURE_CUBE_MAP : ft.isWebGL3DRenderTarget ? t.TEXTURE_3D : ft.isWebGLArrayRenderTarget || ft.isCompressedArrayTexture ? t.TEXTURE_2D_ARRAY : t.TEXTURE_2D;
  }
  function j(ft, $, yt, Tt, Dt = false) {
    if (ft !== null) {
      if (t[ft] !== void 0) return t[ft];
      console.warn("THREE.WebGLRenderer: Attempt to use non-existing WebGL internal format '" + ft + "'");
    }
    let pt = $;
    if ($ === t.RED && (yt === t.FLOAT && (pt = t.R32F), yt === t.HALF_FLOAT && (pt = t.R16F), yt === t.UNSIGNED_BYTE && (pt = t.R8)), $ === t.RED_INTEGER && (yt === t.UNSIGNED_BYTE && (pt = t.R8UI), yt === t.UNSIGNED_SHORT && (pt = t.R16UI), yt === t.UNSIGNED_INT && (pt = t.R32UI), yt === t.BYTE && (pt = t.R8I), yt === t.SHORT && (pt = t.R16I), yt === t.INT && (pt = t.R32I)), $ === t.RG && (yt === t.FLOAT && (pt = t.RG32F), yt === t.HALF_FLOAT && (pt = t.RG16F), yt === t.UNSIGNED_BYTE && (pt = t.RG8)), $ === t.RG_INTEGER && (yt === t.UNSIGNED_BYTE && (pt = t.RG8UI), yt === t.UNSIGNED_SHORT && (pt = t.RG16UI), yt === t.UNSIGNED_INT && (pt = t.RG32UI), yt === t.BYTE && (pt = t.RG8I), yt === t.SHORT && (pt = t.RG16I), yt === t.INT && (pt = t.RG32I)), $ === t.RGB_INTEGER && (yt === t.UNSIGNED_BYTE && (pt = t.RGB8UI), yt === t.UNSIGNED_SHORT && (pt = t.RGB16UI), yt === t.UNSIGNED_INT && (pt = t.RGB32UI), yt === t.BYTE && (pt = t.RGB8I), yt === t.SHORT && (pt = t.RGB16I), yt === t.INT && (pt = t.RGB32I)), $ === t.RGBA_INTEGER && (yt === t.UNSIGNED_BYTE && (pt = t.RGBA8UI), yt === t.UNSIGNED_SHORT && (pt = t.RGBA16UI), yt === t.UNSIGNED_INT && (pt = t.RGBA32UI), yt === t.BYTE && (pt = t.RGBA8I), yt === t.SHORT && (pt = t.RGBA16I), yt === t.INT && (pt = t.RGBA32I)), $ === t.RGB && (yt === t.UNSIGNED_INT_5_9_9_9_REV && (pt = t.RGB9_E5), yt === t.UNSIGNED_INT_10F_11F_11F_REV && (pt = t.R11F_G11F_B10F)), $ === t.RGBA) {
      const ce = Dt ? u6 : Uf.getTransfer(Tt);
      yt === t.FLOAT && (pt = t.RGBA32F), yt === t.HALF_FLOAT && (pt = t.RGBA16F), yt === t.UNSIGNED_BYTE && (pt = ce === dn ? t.SRGB8_ALPHA8 : t.RGBA8), yt === t.UNSIGNED_SHORT_4_4_4_4 && (pt = t.RGBA4), yt === t.UNSIGNED_SHORT_5_5_5_1 && (pt = t.RGB5_A1);
    }
    return (pt === t.R16F || pt === t.R32F || pt === t.RG16F || pt === t.RG32F || pt === t.RGBA16F || pt === t.RGBA32F) && e.get("EXT_color_buffer_float"), pt;
  }
  function x(ft, $) {
    let yt;
    return ft ? $ === null || $ === Hb || $ === Ns ? yt = t.DEPTH24_STENCIL8 : $ === Zz ? yt = t.DEPTH32F_STENCIL8 : $ === Hs && (yt = t.DEPTH24_STENCIL8, console.warn("DepthTexture: 16 bit depth attachment is not supported with stencil. Using 24-bit attachment.")) : $ === null || $ === Hb || $ === Ns ? yt = t.DEPTH_COMPONENT24 : $ === Zz ? yt = t.DEPTH_COMPONENT32F : $ === Hs && (yt = t.DEPTH_COMPONENT16), yt;
  }
  function L(ft, $) {
    return X(ft) === true || ft.isFramebufferTexture && ft.minFilter !== tr && ft.minFilter !== Wz ? Math.log2(Math.max($.width, $.height)) + 1 : ft.mipmaps !== void 0 && ft.mipmaps.length > 0 ? ft.mipmaps.length : ft.isCompressedTexture && Array.isArray(ft.image) ? $.mipmaps.length : 1;
  }
  function O(ft) {
    const $ = ft.target;
    $.removeEventListener("dispose", O), i($), $.isVideoTexture && P.delete($);
  }
  function N(ft) {
    const $ = ft.target;
    $.removeEventListener("dispose", N), M($);
  }
  function i(ft) {
    const $ = n.get(ft);
    if ($.__webglInit === void 0) return;
    const yt = ft.source, Tt = d.get(yt);
    if (Tt) {
      const Dt = Tt[$.__cacheKey];
      Dt.usedTimes--, Dt.usedTimes === 0 && H(ft), Object.keys(Tt).length === 0 && d.delete(yt);
    }
    n.remove(ft);
  }
  function H(ft) {
    const $ = n.get(ft);
    t.deleteTexture($.__webglTexture);
    const yt = ft.source, Tt = d.get(yt);
    delete Tt[$.__cacheKey], z.memory.textures--;
  }
  function M(ft) {
    const $ = n.get(ft);
    if (ft.depthTexture && (ft.depthTexture.dispose(), n.remove(ft.depthTexture)), ft.isWebGLCubeRenderTarget) for (let Tt = 0; Tt < 6; Tt++) {
      if (Array.isArray($.__webglFramebuffer[Tt])) for (let Dt = 0; Dt < $.__webglFramebuffer[Tt].length; Dt++) t.deleteFramebuffer($.__webglFramebuffer[Tt][Dt]);
      else t.deleteFramebuffer($.__webglFramebuffer[Tt]);
      $.__webglDepthbuffer && t.deleteRenderbuffer($.__webglDepthbuffer[Tt]);
    }
    else {
      if (Array.isArray($.__webglFramebuffer)) for (let Tt = 0; Tt < $.__webglFramebuffer.length; Tt++) t.deleteFramebuffer($.__webglFramebuffer[Tt]);
      else t.deleteFramebuffer($.__webglFramebuffer);
      if ($.__webglDepthbuffer && t.deleteRenderbuffer($.__webglDepthbuffer), $.__webglMultisampledFramebuffer && t.deleteFramebuffer($.__webglMultisampledFramebuffer), $.__webglColorRenderbuffer) for (let Tt = 0; Tt < $.__webglColorRenderbuffer.length; Tt++) $.__webglColorRenderbuffer[Tt] && t.deleteRenderbuffer($.__webglColorRenderbuffer[Tt]);
      $.__webglDepthRenderbuffer && t.deleteRenderbuffer($.__webglDepthRenderbuffer);
    }
    const yt = ft.textures;
    for (let Tt = 0, Dt = yt.length; Tt < Dt; Tt++) {
      const pt = n.get(yt[Tt]);
      pt.__webglTexture && (t.deleteTexture(pt.__webglTexture), z.memory.textures--), n.remove(yt[Tt]);
    }
    n.remove(ft);
  }
  let p = 0;
  function T() {
    p = 0;
  }
  function w() {
    const ft = p;
    return ft >= v.maxTextures && console.warn("THREE.WebGLTextures: Trying to use " + ft + " texture units while this GPU supports only " + v.maxTextures), p += 1, ft;
  }
  function Y(ft) {
    const $ = [];
    return $.push(ft.wrapS), $.push(ft.wrapT), $.push(ft.wrapR || 0), $.push(ft.magFilter), $.push(ft.minFilter), $.push(ft.anisotropy), $.push(ft.internalFormat), $.push(ft.format), $.push(ft.type), $.push(ft.generateMipmaps), $.push(ft.premultiplyAlpha), $.push(ft.flipY), $.push(ft.unpackAlignment), $.push(ft.colorSpace), $.join();
  }
  function q(ft, $) {
    const yt = n.get(ft);
    if (ft.isVideoTexture && Mt(ft), ft.isRenderTargetTexture === false && ft.isExternalTexture !== true && ft.version > 0 && yt.__version !== ft.version) {
      const Tt = ft.image;
      if (Tt === null) console.warn("THREE.WebGLRenderer: Texture marked for update but no image data found.");
      else if (Tt.complete === false) console.warn("THREE.WebGLRenderer: Texture marked for update but image is incomplete");
      else {
        Q(yt, ft, $);
        return;
      }
    } else ft.isExternalTexture && (yt.__webglTexture = ft.sourceTexture ? ft.sourceTexture : null);
    f.bindTexture(t.TEXTURE_2D, yt.__webglTexture, t.TEXTURE0 + $);
  }
  function D(ft, $) {
    const yt = n.get(ft);
    if (ft.isRenderTargetTexture === false && ft.version > 0 && yt.__version !== ft.version) {
      Q(yt, ft, $);
      return;
    }
    f.bindTexture(t.TEXTURE_2D_ARRAY, yt.__webglTexture, t.TEXTURE0 + $);
  }
  function V(ft, $) {
    const yt = n.get(ft);
    if (ft.isRenderTargetTexture === false && ft.version > 0 && yt.__version !== ft.version) {
      Q(yt, ft, $);
      return;
    }
    f.bindTexture(t.TEXTURE_3D, yt.__webglTexture, t.TEXTURE0 + $);
  }
  function G(ft, $) {
    const yt = n.get(ft);
    if (ft.version > 0 && yt.__version !== ft.version) {
      B(yt, ft, $);
      return;
    }
    f.bindTexture(t.TEXTURE_CUBE_MAP, yt.__webglTexture, t.TEXTURE0 + $);
  }
  const R = { [e6]: t.REPEAT, [Lb]: t.CLAMP_TO_EDGE, [XN]: t.MIRRORED_REPEAT }, k = { [tr]: t.NEAREST, [jqt]: t.NEAREST_MIPMAP_NEAREST, [f6]: t.NEAREST_MIPMAP_LINEAR, [Wz]: t.LINEAR, [WN]: t.LINEAR_MIPMAP_NEAREST, [Ob]: t.LINEAR_MIPMAP_LINEAR }, K = { [Hqt]: t.NEVER, [oqt]: t.ALWAYS, [Nqt]: t.LESS, [AI]: t.LEQUAL, [Mqt]: t.EQUAL, [Tqt]: t.GEQUAL, [iqt]: t.GREATER, [pqt]: t.NOTEQUAL };
  function _(ft, $) {
    if ($.type === Zz && e.has("OES_texture_float_linear") === false && ($.magFilter === Wz || $.magFilter === WN || $.magFilter === f6 || $.magFilter === Ob || $.minFilter === Wz || $.minFilter === WN || $.minFilter === f6 || $.minFilter === Ob) && console.warn("THREE.WebGLRenderer: Unable to use linear filtering with floating point textures. OES_texture_float_linear not supported on this device."), t.texParameteri(ft, t.TEXTURE_WRAP_S, R[$.wrapS]), t.texParameteri(ft, t.TEXTURE_WRAP_T, R[$.wrapT]), (ft === t.TEXTURE_3D || ft === t.TEXTURE_2D_ARRAY) && t.texParameteri(ft, t.TEXTURE_WRAP_R, R[$.wrapR]), t.texParameteri(ft, t.TEXTURE_MAG_FILTER, k[$.magFilter]), t.texParameteri(ft, t.TEXTURE_MIN_FILTER, k[$.minFilter]), $.compareFunction && (t.texParameteri(ft, t.TEXTURE_COMPARE_MODE, t.COMPARE_REF_TO_TEXTURE), t.texParameteri(ft, t.TEXTURE_COMPARE_FUNC, K[$.compareFunction])), e.has("EXT_texture_filter_anisotropic") === true) {
      if ($.magFilter === tr || $.minFilter !== f6 && $.minFilter !== Ob || $.type === Zz && e.has("OES_texture_float_linear") === false) return;
      if ($.anisotropy > 1 || n.get($).__currentAnisotropy) {
        const yt = e.get("EXT_texture_filter_anisotropic");
        t.texParameterf(ft, yt.TEXTURE_MAX_ANISOTROPY_EXT, Math.min($.anisotropy, v.getMaxAnisotropy())), n.get($).__currentAnisotropy = $.anisotropy;
      }
    }
  }
  function vt(ft, $) {
    let yt = false;
    ft.__webglInit === void 0 && (ft.__webglInit = true, $.addEventListener("dispose", O));
    const Tt = $.source;
    let Dt = d.get(Tt);
    Dt === void 0 && (Dt = {}, d.set(Tt, Dt));
    const pt = Y($);
    if (pt !== ft.__cacheKey) {
      Dt[pt] === void 0 && (Dt[pt] = { texture: t.createTexture(), usedTimes: 0 }, z.memory.textures++, yt = true), Dt[pt].usedTimes++;
      const ce = Dt[ft.__cacheKey];
      ce !== void 0 && (Dt[ft.__cacheKey].usedTimes--, ce.usedTimes === 0 && H($)), ft.__cacheKey = pt, ft.__webglTexture = Dt[pt].texture;
    }
    return yt;
  }
  function rt(ft, $, yt) {
    return Math.floor(Math.floor(ft / yt) / $);
  }
  function A(ft, $, yt, Tt) {
    const pt = ft.updateRanges;
    if (pt.length === 0) f.texSubImage2D(t.TEXTURE_2D, 0, 0, 0, $.width, $.height, yt, Tt, $.data);
    else {
      pt.sort((ne, Le) => ne.start - Le.start);
      let ce = 0;
      for (let ne = 1; ne < pt.length; ne++) {
        const Le = pt[ce], wt = pt[ne], it = Le.start + Le.count, Rt = rt(wt.start, $.width, 4), jt = rt(Le.start, $.width, 4);
        wt.start <= it + 1 && Rt === jt && rt(wt.start + wt.count - 1, $.width, 4) === Rt ? Le.count = Math.max(Le.count, wt.start + wt.count - Le.start) : (++ce, pt[ce] = wt);
      }
      pt.length = ce + 1;
      const ee = t.getParameter(t.UNPACK_ROW_LENGTH), xe = t.getParameter(t.UNPACK_SKIP_PIXELS), me = t.getParameter(t.UNPACK_SKIP_ROWS);
      t.pixelStorei(t.UNPACK_ROW_LENGTH, $.width);
      for (let ne = 0, Le = pt.length; ne < Le; ne++) {
        const wt = pt[ne], it = Math.floor(wt.start / 4), Rt = Math.ceil(wt.count / 4), jt = it % $.width, Xt = Math.floor(it / $.width), ot = Rt, Ut = 1;
        t.pixelStorei(t.UNPACK_SKIP_PIXELS, jt), t.pixelStorei(t.UNPACK_SKIP_ROWS, Xt), f.texSubImage2D(t.TEXTURE_2D, 0, jt, Xt, ot, Ut, yt, Tt, $.data);
      }
      ft.clearUpdateRanges(), t.pixelStorei(t.UNPACK_ROW_LENGTH, ee), t.pixelStorei(t.UNPACK_SKIP_PIXELS, xe), t.pixelStorei(t.UNPACK_SKIP_ROWS, me);
    }
  }
  function Q(ft, $, yt) {
    let Tt = t.TEXTURE_2D;
    ($.isDataArrayTexture || $.isCompressedArrayTexture) && (Tt = t.TEXTURE_2D_ARRAY), $.isData3DTexture && (Tt = t.TEXTURE_3D);
    const Dt = vt(ft, $), pt = $.source;
    f.bindTexture(Tt, ft.__webglTexture, t.TEXTURE0 + yt);
    const ce = n.get(pt);
    if (pt.version !== ce.__version || Dt === true) {
      f.activeTexture(t.TEXTURE0 + yt);
      const ee = Uf.getPrimaries(Uf.workingColorSpace), xe = $.colorSpace === T5 ? null : Uf.getPrimaries($.colorSpace), me = $.colorSpace === T5 || ee === xe ? t.NONE : t.BROWSER_DEFAULT_WEBGL;
      t.pixelStorei(t.UNPACK_FLIP_Y_WEBGL, $.flipY), t.pixelStorei(t.UNPACK_PREMULTIPLY_ALPHA_WEBGL, $.premultiplyAlpha), t.pixelStorei(t.UNPACK_ALIGNMENT, $.unpackAlignment), t.pixelStorei(t.UNPACK_COLORSPACE_CONVERSION_WEBGL, me);
      let ne = W($.image, false, v.maxTextureSize);
      ne = Ht($, ne);
      const Le = r.convert($.format, $.colorSpace), wt = r.convert($.type);
      let it = j($.internalFormat, Le, wt, $.colorSpace, $.isVideoTexture);
      _(Tt, $);
      let Rt;
      const jt = $.mipmaps, Xt = $.isVideoTexture !== true, ot = ce.__version === void 0 || Dt === true, Ut = pt.dataReady, te = L($, ne);
      if ($.isDepthTexture) it = x($.format === is, $.type), ot && (Xt ? f.texStorage2D(t.TEXTURE_2D, 1, it, ne.width, ne.height) : f.texImage2D(t.TEXTURE_2D, 0, it, ne.width, ne.height, 0, Le, wt, null));
      else if ($.isDataTexture) if (jt.length > 0) {
        Xt && ot && f.texStorage2D(t.TEXTURE_2D, te, it, jt[0].width, jt[0].height);
        for (let It = 0, Bt = jt.length; It < Bt; It++) Rt = jt[It], Xt ? Ut && f.texSubImage2D(t.TEXTURE_2D, It, 0, 0, Rt.width, Rt.height, Le, wt, Rt.data) : f.texImage2D(t.TEXTURE_2D, It, it, Rt.width, Rt.height, 0, Le, wt, Rt.data);
        $.generateMipmaps = false;
      } else Xt ? (ot && f.texStorage2D(t.TEXTURE_2D, te, it, ne.width, ne.height), Ut && A($, ne, Le, wt)) : f.texImage2D(t.TEXTURE_2D, 0, it, ne.width, ne.height, 0, Le, wt, ne.data);
      else if ($.isCompressedTexture) if ($.isCompressedArrayTexture) {
        Xt && ot && f.texStorage3D(t.TEXTURE_2D_ARRAY, te, it, jt[0].width, jt[0].height, ne.depth);
        for (let It = 0, Bt = jt.length; It < Bt; It++) if (Rt = jt[It], $.format !== W7) if (Le !== null) if (Xt) {
          if (Ut) if ($.layerUpdates.size > 0) {
            const de = zE(Rt.width, Rt.height, $.format, $.type);
            for (const ve of $.layerUpdates) {
              const Re = Rt.data.subarray(ve * de / Rt.data.BYTES_PER_ELEMENT, (ve + 1) * de / Rt.data.BYTES_PER_ELEMENT);
              f.compressedTexSubImage3D(t.TEXTURE_2D_ARRAY, It, 0, 0, ve, Rt.width, Rt.height, 1, Le, Re);
            }
            $.clearLayerUpdates();
          } else f.compressedTexSubImage3D(t.TEXTURE_2D_ARRAY, It, 0, 0, 0, Rt.width, Rt.height, ne.depth, Le, Rt.data);
        } else f.compressedTexImage3D(t.TEXTURE_2D_ARRAY, It, it, Rt.width, Rt.height, ne.depth, 0, Rt.data, 0, 0);
        else console.warn("THREE.WebGLRenderer: Attempt to load unsupported compressed texture format in .uploadTexture()");
        else Xt ? Ut && f.texSubImage3D(t.TEXTURE_2D_ARRAY, It, 0, 0, 0, Rt.width, Rt.height, ne.depth, Le, wt, Rt.data) : f.texImage3D(t.TEXTURE_2D_ARRAY, It, it, Rt.width, Rt.height, ne.depth, 0, Le, wt, Rt.data);
      } else {
        Xt && ot && f.texStorage2D(t.TEXTURE_2D, te, it, jt[0].width, jt[0].height);
        for (let It = 0, Bt = jt.length; It < Bt; It++) Rt = jt[It], $.format !== W7 ? Le !== null ? Xt ? Ut && f.compressedTexSubImage2D(t.TEXTURE_2D, It, 0, 0, Rt.width, Rt.height, Le, Rt.data) : f.compressedTexImage2D(t.TEXTURE_2D, It, it, Rt.width, Rt.height, 0, Rt.data) : console.warn("THREE.WebGLRenderer: Attempt to load unsupported compressed texture format in .uploadTexture()") : Xt ? Ut && f.texSubImage2D(t.TEXTURE_2D, It, 0, 0, Rt.width, Rt.height, Le, wt, Rt.data) : f.texImage2D(t.TEXTURE_2D, It, it, Rt.width, Rt.height, 0, Le, wt, Rt.data);
      }
      else if ($.isDataArrayTexture) if (Xt) {
        if (ot && f.texStorage3D(t.TEXTURE_2D_ARRAY, te, it, ne.width, ne.height, ne.depth), Ut) if ($.layerUpdates.size > 0) {
          const It = zE(ne.width, ne.height, $.format, $.type);
          for (const Bt of $.layerUpdates) {
            const de = ne.data.subarray(Bt * It / ne.data.BYTES_PER_ELEMENT, (Bt + 1) * It / ne.data.BYTES_PER_ELEMENT);
            f.texSubImage3D(t.TEXTURE_2D_ARRAY, 0, 0, 0, Bt, ne.width, ne.height, 1, Le, wt, de);
          }
          $.clearLayerUpdates();
        } else f.texSubImage3D(t.TEXTURE_2D_ARRAY, 0, 0, 0, 0, ne.width, ne.height, ne.depth, Le, wt, ne.data);
      } else f.texImage3D(t.TEXTURE_2D_ARRAY, 0, it, ne.width, ne.height, ne.depth, 0, Le, wt, ne.data);
      else if ($.isData3DTexture) Xt ? (ot && f.texStorage3D(t.TEXTURE_3D, te, it, ne.width, ne.height, ne.depth), Ut && f.texSubImage3D(t.TEXTURE_3D, 0, 0, 0, 0, ne.width, ne.height, ne.depth, Le, wt, ne.data)) : f.texImage3D(t.TEXTURE_3D, 0, it, ne.width, ne.height, ne.depth, 0, Le, wt, ne.data);
      else if ($.isFramebufferTexture) {
        if (ot) if (Xt) f.texStorage2D(t.TEXTURE_2D, te, it, ne.width, ne.height);
        else {
          let It = ne.width, Bt = ne.height;
          for (let de = 0; de < te; de++) f.texImage2D(t.TEXTURE_2D, de, it, It, Bt, 0, Le, wt, null), It >>= 1, Bt >>= 1;
        }
      } else if (jt.length > 0) {
        if (Xt && ot) {
          const It = Gt(jt[0]);
          f.texStorage2D(t.TEXTURE_2D, te, it, It.width, It.height);
        }
        for (let It = 0, Bt = jt.length; It < Bt; It++) Rt = jt[It], Xt ? Ut && f.texSubImage2D(t.TEXTURE_2D, It, 0, 0, Le, wt, Rt) : f.texImage2D(t.TEXTURE_2D, It, it, Le, wt, Rt);
        $.generateMipmaps = false;
      } else if (Xt) {
        if (ot) {
          const It = Gt(ne);
          f.texStorage2D(t.TEXTURE_2D, te, it, It.width, It.height);
        }
        Ut && f.texSubImage2D(t.TEXTURE_2D, 0, 0, 0, Le, wt, ne);
      } else f.texImage2D(t.TEXTURE_2D, 0, it, Le, wt, ne);
      X($) && y(Tt), ce.__version = pt.version, $.onUpdate && $.onUpdate($);
    }
    ft.__version = $.version;
  }
  function B(ft, $, yt) {
    if ($.image.length !== 6) return;
    const Tt = vt(ft, $), Dt = $.source;
    f.bindTexture(t.TEXTURE_CUBE_MAP, ft.__webglTexture, t.TEXTURE0 + yt);
    const pt = n.get(Dt);
    if (Dt.version !== pt.__version || Tt === true) {
      f.activeTexture(t.TEXTURE0 + yt);
      const ce = Uf.getPrimaries(Uf.workingColorSpace), ee = $.colorSpace === T5 ? null : Uf.getPrimaries($.colorSpace), xe = $.colorSpace === T5 || ce === ee ? t.NONE : t.BROWSER_DEFAULT_WEBGL;
      t.pixelStorei(t.UNPACK_FLIP_Y_WEBGL, $.flipY), t.pixelStorei(t.UNPACK_PREMULTIPLY_ALPHA_WEBGL, $.premultiplyAlpha), t.pixelStorei(t.UNPACK_ALIGNMENT, $.unpackAlignment), t.pixelStorei(t.UNPACK_COLORSPACE_CONVERSION_WEBGL, xe);
      const me = $.isCompressedTexture || $.image[0].isCompressedTexture, ne = $.image[0] && $.image[0].isDataTexture, Le = [];
      for (let Bt = 0; Bt < 6; Bt++) !me && !ne ? Le[Bt] = W($.image[Bt], true, v.maxCubemapSize) : Le[Bt] = ne ? $.image[Bt].image : $.image[Bt], Le[Bt] = Ht($, Le[Bt]);
      const wt = Le[0], it = r.convert($.format, $.colorSpace), Rt = r.convert($.type), jt = j($.internalFormat, it, Rt, $.colorSpace), Xt = $.isVideoTexture !== true, ot = pt.__version === void 0 || Tt === true, Ut = Dt.dataReady;
      let te = L($, wt);
      _(t.TEXTURE_CUBE_MAP, $);
      let It;
      if (me) {
        Xt && ot && f.texStorage2D(t.TEXTURE_CUBE_MAP, te, jt, wt.width, wt.height);
        for (let Bt = 0; Bt < 6; Bt++) {
          It = Le[Bt].mipmaps;
          for (let de = 0; de < It.length; de++) {
            const ve = It[de];
            $.format !== W7 ? it !== null ? Xt ? Ut && f.compressedTexSubImage2D(t.TEXTURE_CUBE_MAP_POSITIVE_X + Bt, de, 0, 0, ve.width, ve.height, it, ve.data) : f.compressedTexImage2D(t.TEXTURE_CUBE_MAP_POSITIVE_X + Bt, de, jt, ve.width, ve.height, 0, ve.data) : console.warn("THREE.WebGLRenderer: Attempt to load unsupported compressed texture format in .setTextureCube()") : Xt ? Ut && f.texSubImage2D(t.TEXTURE_CUBE_MAP_POSITIVE_X + Bt, de, 0, 0, ve.width, ve.height, it, Rt, ve.data) : f.texImage2D(t.TEXTURE_CUBE_MAP_POSITIVE_X + Bt, de, jt, ve.width, ve.height, 0, it, Rt, ve.data);
          }
        }
      } else {
        if (It = $.mipmaps, Xt && ot) {
          It.length > 0 && te++;
          const Bt = Gt(Le[0]);
          f.texStorage2D(t.TEXTURE_CUBE_MAP, te, jt, Bt.width, Bt.height);
        }
        for (let Bt = 0; Bt < 6; Bt++) if (ne) {
          Xt ? Ut && f.texSubImage2D(t.TEXTURE_CUBE_MAP_POSITIVE_X + Bt, 0, 0, 0, Le[Bt].width, Le[Bt].height, it, Rt, Le[Bt].data) : f.texImage2D(t.TEXTURE_CUBE_MAP_POSITIVE_X + Bt, 0, jt, Le[Bt].width, Le[Bt].height, 0, it, Rt, Le[Bt].data);
          for (let de = 0; de < It.length; de++) {
            const Re = It[de].image[Bt].image;
            Xt ? Ut && f.texSubImage2D(t.TEXTURE_CUBE_MAP_POSITIVE_X + Bt, de + 1, 0, 0, Re.width, Re.height, it, Rt, Re.data) : f.texImage2D(t.TEXTURE_CUBE_MAP_POSITIVE_X + Bt, de + 1, jt, Re.width, Re.height, 0, it, Rt, Re.data);
          }
        } else {
          Xt ? Ut && f.texSubImage2D(t.TEXTURE_CUBE_MAP_POSITIVE_X + Bt, 0, 0, 0, it, Rt, Le[Bt]) : f.texImage2D(t.TEXTURE_CUBE_MAP_POSITIVE_X + Bt, 0, jt, it, Rt, Le[Bt]);
          for (let de = 0; de < It.length; de++) {
            const ve = It[de];
            Xt ? Ut && f.texSubImage2D(t.TEXTURE_CUBE_MAP_POSITIVE_X + Bt, de + 1, 0, 0, it, Rt, ve.image[Bt]) : f.texImage2D(t.TEXTURE_CUBE_MAP_POSITIVE_X + Bt, de + 1, jt, it, Rt, ve.image[Bt]);
          }
        }
      }
      X($) && y(t.TEXTURE_CUBE_MAP), pt.__version = Dt.version, $.onUpdate && $.onUpdate($);
    }
    ft.__version = $.version;
  }
  function et(ft, $, yt, Tt, Dt, pt) {
    const ce = r.convert(yt.format, yt.colorSpace), ee = r.convert(yt.type), xe = j(yt.internalFormat, ce, ee, yt.colorSpace), me = n.get($), ne = n.get(yt);
    if (ne.__renderTarget = $, !me.__hasExternalTextures) {
      const Le = Math.max(1, $.width >> pt), wt = Math.max(1, $.height >> pt);
      Dt === t.TEXTURE_3D || Dt === t.TEXTURE_2D_ARRAY ? f.texImage3D(Dt, pt, xe, Le, wt, $.depth, 0, ce, ee, null) : f.texImage2D(Dt, pt, xe, Le, wt, 0, ce, ee, null);
    }
    f.bindFramebuffer(t.FRAMEBUFFER, ft), Lt($) ? u.framebufferTexture2DMultisampleEXT(t.FRAMEBUFFER, Tt, Dt, ne.__webglTexture, 0, xt($)) : (Dt === t.TEXTURE_2D || Dt >= t.TEXTURE_CUBE_MAP_POSITIVE_X && Dt <= t.TEXTURE_CUBE_MAP_NEGATIVE_Z) && t.framebufferTexture2D(t.FRAMEBUFFER, Tt, Dt, ne.__webglTexture, pt), f.bindFramebuffer(t.FRAMEBUFFER, null);
  }
  function nt(ft, $, yt) {
    if (t.bindRenderbuffer(t.RENDERBUFFER, ft), $.depthBuffer) {
      const Tt = $.depthTexture, Dt = Tt && Tt.isDepthTexture ? Tt.type : null, pt = x($.stencilBuffer, Dt), ce = $.stencilBuffer ? t.DEPTH_STENCIL_ATTACHMENT : t.DEPTH_ATTACHMENT, ee = xt($);
      Lt($) ? u.renderbufferStorageMultisampleEXT(t.RENDERBUFFER, ee, pt, $.width, $.height) : yt ? t.renderbufferStorageMultisample(t.RENDERBUFFER, ee, pt, $.width, $.height) : t.renderbufferStorage(t.RENDERBUFFER, pt, $.width, $.height), t.framebufferRenderbuffer(t.FRAMEBUFFER, ce, t.RENDERBUFFER, ft);
    } else {
      const Tt = $.textures;
      for (let Dt = 0; Dt < Tt.length; Dt++) {
        const pt = Tt[Dt], ce = r.convert(pt.format, pt.colorSpace), ee = r.convert(pt.type), xe = j(pt.internalFormat, ce, ee, pt.colorSpace), me = xt($);
        yt && Lt($) === false ? t.renderbufferStorageMultisample(t.RENDERBUFFER, me, xe, $.width, $.height) : Lt($) ? u.renderbufferStorageMultisampleEXT(t.RENDERBUFFER, me, xe, $.width, $.height) : t.renderbufferStorage(t.RENDERBUFFER, xe, $.width, $.height);
      }
    }
    t.bindRenderbuffer(t.RENDERBUFFER, null);
  }
  function I(ft, $) {
    if ($ && $.isWebGLCubeRenderTarget) throw new Error("Depth Texture with cube render targets is not supported");
    if (f.bindFramebuffer(t.FRAMEBUFFER, ft), !($.depthTexture && $.depthTexture.isDepthTexture)) throw new Error("renderTarget.depthTexture must be an instance of THREE.DepthTexture");
    const Tt = n.get($.depthTexture);
    Tt.__renderTarget = $, (!Tt.__webglTexture || $.depthTexture.image.width !== $.width || $.depthTexture.image.height !== $.height) && ($.depthTexture.image.width = $.width, $.depthTexture.image.height = $.height, $.depthTexture.needsUpdate = true), q($.depthTexture, 0);
    const Dt = Tt.__webglTexture, pt = xt($);
    if ($.depthTexture.format === Ms) Lt($) ? u.framebufferTexture2DMultisampleEXT(t.FRAMEBUFFER, t.DEPTH_ATTACHMENT, t.TEXTURE_2D, Dt, 0, pt) : t.framebufferTexture2D(t.FRAMEBUFFER, t.DEPTH_ATTACHMENT, t.TEXTURE_2D, Dt, 0);
    else if ($.depthTexture.format === is) Lt($) ? u.framebufferTexture2DMultisampleEXT(t.FRAMEBUFFER, t.DEPTH_STENCIL_ATTACHMENT, t.TEXTURE_2D, Dt, 0, pt) : t.framebufferTexture2D(t.FRAMEBUFFER, t.DEPTH_STENCIL_ATTACHMENT, t.TEXTURE_2D, Dt, 0);
    else throw new Error("Unknown depthTexture format");
  }
  function U(ft) {
    const $ = n.get(ft), yt = ft.isWebGLCubeRenderTarget === true;
    if ($.__boundDepthTexture !== ft.depthTexture) {
      const Tt = ft.depthTexture;
      if ($.__depthDisposeCallback && $.__depthDisposeCallback(), Tt) {
        const Dt = () => {
          delete $.__boundDepthTexture, delete $.__depthDisposeCallback, Tt.removeEventListener("dispose", Dt);
        };
        Tt.addEventListener("dispose", Dt), $.__depthDisposeCallback = Dt;
      }
      $.__boundDepthTexture = Tt;
    }
    if (ft.depthTexture && !$.__autoAllocateDepthBuffer) {
      if (yt) throw new Error("target.depthTexture not supported in Cube render targets");
      const Tt = ft.texture.mipmaps;
      Tt && Tt.length > 0 ? I($.__webglFramebuffer[0], ft) : I($.__webglFramebuffer, ft);
    } else if (yt) {
      $.__webglDepthbuffer = [];
      for (let Tt = 0; Tt < 6; Tt++) if (f.bindFramebuffer(t.FRAMEBUFFER, $.__webglFramebuffer[Tt]), $.__webglDepthbuffer[Tt] === void 0) $.__webglDepthbuffer[Tt] = t.createRenderbuffer(), nt($.__webglDepthbuffer[Tt], ft, false);
      else {
        const Dt = ft.stencilBuffer ? t.DEPTH_STENCIL_ATTACHMENT : t.DEPTH_ATTACHMENT, pt = $.__webglDepthbuffer[Tt];
        t.bindRenderbuffer(t.RENDERBUFFER, pt), t.framebufferRenderbuffer(t.FRAMEBUFFER, Dt, t.RENDERBUFFER, pt);
      }
    } else {
      const Tt = ft.texture.mipmaps;
      if (Tt && Tt.length > 0 ? f.bindFramebuffer(t.FRAMEBUFFER, $.__webglFramebuffer[0]) : f.bindFramebuffer(t.FRAMEBUFFER, $.__webglFramebuffer), $.__webglDepthbuffer === void 0) $.__webglDepthbuffer = t.createRenderbuffer(), nt($.__webglDepthbuffer, ft, false);
      else {
        const Dt = ft.stencilBuffer ? t.DEPTH_STENCIL_ATTACHMENT : t.DEPTH_ATTACHMENT, pt = $.__webglDepthbuffer;
        t.bindRenderbuffer(t.RENDERBUFFER, pt), t.framebufferRenderbuffer(t.FRAMEBUFFER, Dt, t.RENDERBUFFER, pt);
      }
    }
    f.bindFramebuffer(t.FRAMEBUFFER, null);
  }
  function J(ft, $, yt) {
    const Tt = n.get(ft);
    $ !== void 0 && et(Tt.__webglFramebuffer, ft, ft.texture, t.COLOR_ATTACHMENT0, t.TEXTURE_2D, 0), yt !== void 0 && U(ft);
  }
  function g(ft) {
    const $ = ft.texture, yt = n.get(ft), Tt = n.get($);
    ft.addEventListener("dispose", N);
    const Dt = ft.textures, pt = ft.isWebGLCubeRenderTarget === true, ce = Dt.length > 1;
    if (ce || (Tt.__webglTexture === void 0 && (Tt.__webglTexture = t.createTexture()), Tt.__version = $.version, z.memory.textures++), pt) {
      yt.__webglFramebuffer = [];
      for (let ee = 0; ee < 6; ee++) if ($.mipmaps && $.mipmaps.length > 0) {
        yt.__webglFramebuffer[ee] = [];
        for (let xe = 0; xe < $.mipmaps.length; xe++) yt.__webglFramebuffer[ee][xe] = t.createFramebuffer();
      } else yt.__webglFramebuffer[ee] = t.createFramebuffer();
    } else {
      if ($.mipmaps && $.mipmaps.length > 0) {
        yt.__webglFramebuffer = [];
        for (let ee = 0; ee < $.mipmaps.length; ee++) yt.__webglFramebuffer[ee] = t.createFramebuffer();
      } else yt.__webglFramebuffer = t.createFramebuffer();
      if (ce) for (let ee = 0, xe = Dt.length; ee < xe; ee++) {
        const me = n.get(Dt[ee]);
        me.__webglTexture === void 0 && (me.__webglTexture = t.createTexture(), z.memory.textures++);
      }
      if (ft.samples > 0 && Lt(ft) === false) {
        yt.__webglMultisampledFramebuffer = t.createFramebuffer(), yt.__webglColorRenderbuffer = [], f.bindFramebuffer(t.FRAMEBUFFER, yt.__webglMultisampledFramebuffer);
        for (let ee = 0; ee < Dt.length; ee++) {
          const xe = Dt[ee];
          yt.__webglColorRenderbuffer[ee] = t.createRenderbuffer(), t.bindRenderbuffer(t.RENDERBUFFER, yt.__webglColorRenderbuffer[ee]);
          const me = r.convert(xe.format, xe.colorSpace), ne = r.convert(xe.type), Le = j(xe.internalFormat, me, ne, xe.colorSpace, ft.isXRRenderTarget === true), wt = xt(ft);
          t.renderbufferStorageMultisample(t.RENDERBUFFER, wt, Le, ft.width, ft.height), t.framebufferRenderbuffer(t.FRAMEBUFFER, t.COLOR_ATTACHMENT0 + ee, t.RENDERBUFFER, yt.__webglColorRenderbuffer[ee]);
        }
        t.bindRenderbuffer(t.RENDERBUFFER, null), ft.depthBuffer && (yt.__webglDepthRenderbuffer = t.createRenderbuffer(), nt(yt.__webglDepthRenderbuffer, ft, true)), f.bindFramebuffer(t.FRAMEBUFFER, null);
      }
    }
    if (pt) {
      f.bindTexture(t.TEXTURE_CUBE_MAP, Tt.__webglTexture), _(t.TEXTURE_CUBE_MAP, $);
      for (let ee = 0; ee < 6; ee++) if ($.mipmaps && $.mipmaps.length > 0) for (let xe = 0; xe < $.mipmaps.length; xe++) et(yt.__webglFramebuffer[ee][xe], ft, $, t.COLOR_ATTACHMENT0, t.TEXTURE_CUBE_MAP_POSITIVE_X + ee, xe);
      else et(yt.__webglFramebuffer[ee], ft, $, t.COLOR_ATTACHMENT0, t.TEXTURE_CUBE_MAP_POSITIVE_X + ee, 0);
      X($) && y(t.TEXTURE_CUBE_MAP), f.unbindTexture();
    } else if (ce) {
      for (let ee = 0, xe = Dt.length; ee < xe; ee++) {
        const me = Dt[ee], ne = n.get(me);
        let Le = t.TEXTURE_2D;
        (ft.isWebGL3DRenderTarget || ft.isWebGLArrayRenderTarget) && (Le = ft.isWebGL3DRenderTarget ? t.TEXTURE_3D : t.TEXTURE_2D_ARRAY), f.bindTexture(Le, ne.__webglTexture), _(Le, me), et(yt.__webglFramebuffer, ft, me, t.COLOR_ATTACHMENT0 + ee, Le, 0), X(me) && y(Le);
      }
      f.unbindTexture();
    } else {
      let ee = t.TEXTURE_2D;
      if ((ft.isWebGL3DRenderTarget || ft.isWebGLArrayRenderTarget) && (ee = ft.isWebGL3DRenderTarget ? t.TEXTURE_3D : t.TEXTURE_2D_ARRAY), f.bindTexture(ee, Tt.__webglTexture), _(ee, $), $.mipmaps && $.mipmaps.length > 0) for (let xe = 0; xe < $.mipmaps.length; xe++) et(yt.__webglFramebuffer[xe], ft, $, t.COLOR_ATTACHMENT0, ee, xe);
      else et(yt.__webglFramebuffer, ft, $, t.COLOR_ATTACHMENT0, ee, 0);
      X($) && y(ee), f.unbindTexture();
    }
    ft.depthBuffer && U(ft);
  }
  function tt(ft) {
    const $ = ft.textures;
    for (let yt = 0, Tt = $.length; yt < Tt; yt++) {
      const Dt = $[yt];
      if (X(Dt)) {
        const pt = m(ft), ce = n.get(Dt).__webglTexture;
        f.bindTexture(pt, ce), y(pt), f.unbindTexture();
      }
    }
  }
  const zt = [], ut = [];
  function Pt(ft) {
    if (ft.samples > 0) {
      if (Lt(ft) === false) {
        const $ = ft.textures, yt = ft.width, Tt = ft.height;
        let Dt = t.COLOR_BUFFER_BIT;
        const pt = ft.stencilBuffer ? t.DEPTH_STENCIL_ATTACHMENT : t.DEPTH_ATTACHMENT, ce = n.get(ft), ee = $.length > 1;
        if (ee) for (let me = 0; me < $.length; me++) f.bindFramebuffer(t.FRAMEBUFFER, ce.__webglMultisampledFramebuffer), t.framebufferRenderbuffer(t.FRAMEBUFFER, t.COLOR_ATTACHMENT0 + me, t.RENDERBUFFER, null), f.bindFramebuffer(t.FRAMEBUFFER, ce.__webglFramebuffer), t.framebufferTexture2D(t.DRAW_FRAMEBUFFER, t.COLOR_ATTACHMENT0 + me, t.TEXTURE_2D, null, 0);
        f.bindFramebuffer(t.READ_FRAMEBUFFER, ce.__webglMultisampledFramebuffer);
        const xe = ft.texture.mipmaps;
        xe && xe.length > 0 ? f.bindFramebuffer(t.DRAW_FRAMEBUFFER, ce.__webglFramebuffer[0]) : f.bindFramebuffer(t.DRAW_FRAMEBUFFER, ce.__webglFramebuffer);
        for (let me = 0; me < $.length; me++) {
          if (ft.resolveDepthBuffer && (ft.depthBuffer && (Dt |= t.DEPTH_BUFFER_BIT), ft.stencilBuffer && ft.resolveStencilBuffer && (Dt |= t.STENCIL_BUFFER_BIT)), ee) {
            t.framebufferRenderbuffer(t.READ_FRAMEBUFFER, t.COLOR_ATTACHMENT0, t.RENDERBUFFER, ce.__webglColorRenderbuffer[me]);
            const ne = n.get($[me]).__webglTexture;
            t.framebufferTexture2D(t.DRAW_FRAMEBUFFER, t.COLOR_ATTACHMENT0, t.TEXTURE_2D, ne, 0);
          }
          t.blitFramebuffer(0, 0, yt, Tt, 0, 0, yt, Tt, Dt, t.NEAREST), b === true && (zt.length = 0, ut.length = 0, zt.push(t.COLOR_ATTACHMENT0 + me), ft.depthBuffer && ft.resolveDepthBuffer === false && (zt.push(pt), ut.push(pt), t.invalidateFramebuffer(t.DRAW_FRAMEBUFFER, ut)), t.invalidateFramebuffer(t.READ_FRAMEBUFFER, zt));
        }
        if (f.bindFramebuffer(t.READ_FRAMEBUFFER, null), f.bindFramebuffer(t.DRAW_FRAMEBUFFER, null), ee) for (let me = 0; me < $.length; me++) {
          f.bindFramebuffer(t.FRAMEBUFFER, ce.__webglMultisampledFramebuffer), t.framebufferRenderbuffer(t.FRAMEBUFFER, t.COLOR_ATTACHMENT0 + me, t.RENDERBUFFER, ce.__webglColorRenderbuffer[me]);
          const ne = n.get($[me]).__webglTexture;
          f.bindFramebuffer(t.FRAMEBUFFER, ce.__webglFramebuffer), t.framebufferTexture2D(t.DRAW_FRAMEBUFFER, t.COLOR_ATTACHMENT0 + me, t.TEXTURE_2D, ne, 0);
        }
        f.bindFramebuffer(t.DRAW_FRAMEBUFFER, ce.__webglMultisampledFramebuffer);
      } else if (ft.depthBuffer && ft.resolveDepthBuffer === false && b) {
        const $ = ft.stencilBuffer ? t.DEPTH_STENCIL_ATTACHMENT : t.DEPTH_ATTACHMENT;
        t.invalidateFramebuffer(t.DRAW_FRAMEBUFFER, [$]);
      }
    }
  }
  function xt(ft) {
    return Math.min(v.maxSamples, ft.samples);
  }
  function Lt(ft) {
    const $ = n.get(ft);
    return ft.samples > 0 && e.has("WEBGL_multisampled_render_to_texture") === true && $.__useRenderToTexture !== false;
  }
  function Mt(ft) {
    const $ = z.render.frame;
    P.get(ft) !== $ && (P.set(ft, $), ft.update());
  }
  function Ht(ft, $) {
    const yt = ft.colorSpace, Tt = ft.format, Dt = ft.type;
    return ft.isCompressedTexture === true || ft.isVideoTexture === true || yt !== Ua && yt !== T5 && (Uf.getTransfer(yt) === dn ? (Tt !== W7 || Dt !== yz) && console.warn("THREE.WebGLTextures: sRGB encoded textures have to use RGBAFormat and UnsignedByteType.") : console.error("THREE.WebGLTextures: Unsupported texture color space:", yt)), $;
  }
  function Gt(ft) {
    return typeof HTMLImageElement < "u" && ft instanceof HTMLImageElement ? (a.width = ft.naturalWidth || ft.width, a.height = ft.naturalHeight || ft.height) : typeof VideoFrame < "u" && ft instanceof VideoFrame ? (a.width = ft.displayWidth, a.height = ft.displayHeight) : (a.width = ft.width, a.height = ft.height), a;
  }
  this.allocateTextureUnit = w, this.resetTextureUnits = T, this.setTexture2D = q, this.setTexture2DArray = D, this.setTexture3D = V, this.setTextureCube = G, this.rebindTextures = J, this.setupRenderTarget = g, this.updateRenderTargetMipmap = tt, this.updateMultisampleRenderTarget = Pt, this.setupDepthRenderbuffer = U, this.setupFrameBufferTexture = et, this.useMultisampledRTT = Lt;
}
function sSt(t, e) {
  function f(n, v = T5) {
    let r;
    const z = Uf.getTransfer(v);
    if (n === yz) return t.UNSIGNED_BYTE;
    if (n === ZN) return t.UNSIGNED_SHORT_4_4_4_4;
    if (n === mN) return t.UNSIGNED_SHORT_5_5_5_1;
    if (n === SI) return t.UNSIGNED_INT_5_9_9_9_REV;
    if (n === BI) return t.UNSIGNED_INT_10F_11F_11F_REV;
    if (n === JI) return t.BYTE;
    if (n === gI) return t.SHORT;
    if (n === Hs) return t.UNSIGNED_SHORT;
    if (n === yN) return t.INT;
    if (n === Hb) return t.UNSIGNED_INT;
    if (n === Zz) return t.FLOAT;
    if (n === Ba) return t.HALF_FLOAT;
    if (n === UI) return t.ALPHA;
    if (n === II) return t.RGB;
    if (n === W7) return t.RGBA;
    if (n === Ms) return t.DEPTH_COMPONENT;
    if (n === is) return t.DEPTH_STENCIL;
    if (n === jN) return t.RED;
    if (n === xN) return t.RED_INTEGER;
    if (n === KI) return t.RG;
    if (n === LN) return t.RG_INTEGER;
    if (n === ON) return t.RGBA_INTEGER;
    if (n === n6 || n === v6 || n === r6 || n === z6) if (z === dn) if (r = e.get("WEBGL_compressed_texture_s3tc_srgb"), r !== null) {
      if (n === n6) return r.COMPRESSED_SRGB_S3TC_DXT1_EXT;
      if (n === v6) return r.COMPRESSED_SRGB_ALPHA_S3TC_DXT1_EXT;
      if (n === r6) return r.COMPRESSED_SRGB_ALPHA_S3TC_DXT3_EXT;
      if (n === z6) return r.COMPRESSED_SRGB_ALPHA_S3TC_DXT5_EXT;
    } else return null;
    else if (r = e.get("WEBGL_compressed_texture_s3tc"), r !== null) {
      if (n === n6) return r.COMPRESSED_RGB_S3TC_DXT1_EXT;
      if (n === v6) return r.COMPRESSED_RGBA_S3TC_DXT1_EXT;
      if (n === r6) return r.COMPRESSED_RGBA_S3TC_DXT3_EXT;
      if (n === z6) return r.COMPRESSED_RGBA_S3TC_DXT5_EXT;
    } else return null;
    if (n === HN || n === NN || n === MN || n === iN) if (r = e.get("WEBGL_compressed_texture_pvrtc"), r !== null) {
      if (n === HN) return r.COMPRESSED_RGB_PVRTC_4BPPV1_IMG;
      if (n === NN) return r.COMPRESSED_RGB_PVRTC_2BPPV1_IMG;
      if (n === MN) return r.COMPRESSED_RGBA_PVRTC_4BPPV1_IMG;
      if (n === iN) return r.COMPRESSED_RGBA_PVRTC_2BPPV1_IMG;
    } else return null;
    if (n === pN || n === TN || n === oN) if (r = e.get("WEBGL_compressed_texture_etc"), r !== null) {
      if (n === pN || n === TN) return z === dn ? r.COMPRESSED_SRGB8_ETC2 : r.COMPRESSED_RGB8_ETC2;
      if (n === oN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ETC2_EAC : r.COMPRESSED_RGBA8_ETC2_EAC;
    } else return null;
    if (n === wN || n === hN || n === GN || n === VN || n === YN || n === DN || n === qN || n === FN || n === RN || n === kN || n === JN || n === gN || n === SN || n === BN) if (r = e.get("WEBGL_compressed_texture_astc"), r !== null) {
      if (n === wN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ASTC_4x4_KHR : r.COMPRESSED_RGBA_ASTC_4x4_KHR;
      if (n === hN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ASTC_5x4_KHR : r.COMPRESSED_RGBA_ASTC_5x4_KHR;
      if (n === GN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ASTC_5x5_KHR : r.COMPRESSED_RGBA_ASTC_5x5_KHR;
      if (n === VN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ASTC_6x5_KHR : r.COMPRESSED_RGBA_ASTC_6x5_KHR;
      if (n === YN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ASTC_6x6_KHR : r.COMPRESSED_RGBA_ASTC_6x6_KHR;
      if (n === DN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ASTC_8x5_KHR : r.COMPRESSED_RGBA_ASTC_8x5_KHR;
      if (n === qN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ASTC_8x6_KHR : r.COMPRESSED_RGBA_ASTC_8x6_KHR;
      if (n === FN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ASTC_8x8_KHR : r.COMPRESSED_RGBA_ASTC_8x8_KHR;
      if (n === RN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ASTC_10x5_KHR : r.COMPRESSED_RGBA_ASTC_10x5_KHR;
      if (n === kN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ASTC_10x6_KHR : r.COMPRESSED_RGBA_ASTC_10x6_KHR;
      if (n === JN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ASTC_10x8_KHR : r.COMPRESSED_RGBA_ASTC_10x8_KHR;
      if (n === gN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ASTC_10x10_KHR : r.COMPRESSED_RGBA_ASTC_10x10_KHR;
      if (n === SN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ASTC_12x10_KHR : r.COMPRESSED_RGBA_ASTC_12x10_KHR;
      if (n === BN) return z === dn ? r.COMPRESSED_SRGB8_ALPHA8_ASTC_12x12_KHR : r.COMPRESSED_RGBA_ASTC_12x12_KHR;
    } else return null;
    if (n === UN || n === IN || n === KN) if (r = e.get("EXT_texture_compression_bptc"), r !== null) {
      if (n === UN) return z === dn ? r.COMPRESSED_SRGB_ALPHA_BPTC_UNORM_EXT : r.COMPRESSED_RGBA_BPTC_UNORM_EXT;
      if (n === IN) return r.COMPRESSED_RGB_BPTC_SIGNED_FLOAT_EXT;
      if (n === KN) return r.COMPRESSED_RGB_BPTC_UNSIGNED_FLOAT_EXT;
    } else return null;
    if (n === EN || n === CN || n === AN || n === QN) if (r = e.get("EXT_texture_compression_rgtc"), r !== null) {
      if (n === EN) return r.COMPRESSED_RED_RGTC1_EXT;
      if (n === CN) return r.COMPRESSED_SIGNED_RED_RGTC1_EXT;
      if (n === AN) return r.COMPRESSED_RED_GREEN_RGTC2_EXT;
      if (n === QN) return r.COMPRESSED_SIGNED_RED_GREEN_RGTC2_EXT;
    } else return null;
    return n === Ns ? t.UNSIGNED_INT_24_8 : t[n] !== void 0 ? t[n] : null;
  }
  return { convert: f };
}
const dSt = `
void main() {

	gl_Position = vec4( position, 1.0 );

}`;
const cSt = `
uniform sampler2DArray depthColor;
uniform float depthWidth;
uniform float depthHeight;

void main() {

	vec2 coord = vec2( gl_FragCoord.x / depthWidth, gl_FragCoord.y / depthHeight );

	if ( coord.x >= 1.0 ) {

		gl_FragDepth = texture( depthColor, vec3( coord.x - 1.0, coord.y, 1 ) ).r;

	} else {

		gl_FragDepth = texture( depthColor, vec3( coord.x, coord.y, 0 ) ).r;

	}

}`;
class lSt {
  constructor() {
    this.texture = null, this.mesh = null, this.depthNear = 0, this.depthFar = 0;
  }
  init(e, f) {
    if (this.texture === null) {
      const n = new RK(e.texture);
      (e.depthNear !== f.depthNear || e.depthFar !== f.depthFar) && (this.depthNear = e.depthNear, this.depthFar = e.depthFar), this.texture = n;
    }
  }
  getMesh(e) {
    if (this.texture !== null && this.mesh === null) {
      const f = e.cameras[0].viewport, n = new Y5({ vertexShader: dSt, fragmentShader: cSt, uniforms: { depthColor: { value: this.texture }, depthWidth: { value: f.z }, depthHeight: { value: f.w } } });
      this.mesh = new Zn(new j9(20, 20), n);
    }
    return this.mesh;
  }
  reset() {
    this.texture = null, this.mesh = null;
  }
  getDepthTexture() {
    return this.texture;
  }
}
class XSt extends Nb {
  constructor(e, f) {
    super();
    const n = this;
    let v = null, r = 1, z = null, u = "local-floor", b = 1, a = null, P = null, s = null, d = null, c = null, l = null;
    const W = typeof XRWebGLBinding < "u", X = new lSt(), y = {}, m = f.getContextAttributes();
    let j = null, x = null;
    const L = [], O = [], N = new Me();
    let i = null;
    const H = new qr();
    H.viewport = new Un();
    const M = new qr();
    M.viewport = new Un();
    const p = [H, M], T = new cRt();
    let w = null, Y = null;
    this.cameraAutoUpdate = true, this.enabled = false, this.isPresenting = false, this.getController = function(Q) {
      let B = L[Q];
      return B === void 0 && (B = new LM(), L[Q] = B), B.getTargetRaySpace();
    }, this.getControllerGrip = function(Q) {
      let B = L[Q];
      return B === void 0 && (B = new LM(), L[Q] = B), B.getGripSpace();
    }, this.getHand = function(Q) {
      let B = L[Q];
      return B === void 0 && (B = new LM(), L[Q] = B), B.getHandSpace();
    };
    function q(Q) {
      const B = O.indexOf(Q.inputSource);
      if (B === -1) return;
      const et = L[B];
      et !== void 0 && (et.update(Q.inputSource, Q.frame, a || z), et.dispatchEvent({ type: Q.type, data: Q.inputSource }));
    }
    function D() {
      v.removeEventListener("select", q), v.removeEventListener("selectstart", q), v.removeEventListener("selectend", q), v.removeEventListener("squeeze", q), v.removeEventListener("squeezestart", q), v.removeEventListener("squeezeend", q), v.removeEventListener("end", D), v.removeEventListener("inputsourceschange", V);
      for (let Q = 0; Q < L.length; Q++) {
        const B = O[Q];
        B !== null && (O[Q] = null, L[Q].disconnect(B));
      }
      w = null, Y = null, X.reset();
      for (const Q in y) delete y[Q];
      e.setRenderTarget(j), c = null, d = null, s = null, v = null, x = null, A.stop(), n.isPresenting = false, e.setPixelRatio(i), e.setSize(N.width, N.height, false), n.dispatchEvent({ type: "sessionend" });
    }
    this.setFramebufferScaleFactor = function(Q) {
      r = Q, n.isPresenting === true && console.warn("THREE.WebXRManager: Cannot change framebuffer scale while presenting.");
    }, this.setReferenceSpaceType = function(Q) {
      u = Q, n.isPresenting === true && console.warn("THREE.WebXRManager: Cannot change reference space type while presenting.");
    }, this.getReferenceSpace = function() {
      return a || z;
    }, this.setReferenceSpace = function(Q) {
      a = Q;
    }, this.getBaseLayer = function() {
      return d !== null ? d : c;
    }, this.getBinding = function() {
      return s === null && W && (s = new XRWebGLBinding(v, f)), s;
    }, this.getFrame = function() {
      return l;
    }, this.getSession = function() {
      return v;
    }, this.setSession = async function(Q) {
      if (v = Q, v !== null) {
        if (j = e.getRenderTarget(), v.addEventListener("select", q), v.addEventListener("selectstart", q), v.addEventListener("selectend", q), v.addEventListener("squeeze", q), v.addEventListener("squeezestart", q), v.addEventListener("squeezeend", q), v.addEventListener("end", D), v.addEventListener("inputsourceschange", V), m.xrCompatible !== true && await f.makeXRCompatible(), i = e.getPixelRatio(), e.getSize(N), W && "createProjectionLayer" in XRWebGLBinding.prototype) {
          let et = null, nt = null, I = null;
          m.depth && (I = m.stencil ? f.DEPTH24_STENCIL8 : f.DEPTH_COMPONENT24, et = m.stencil ? is : Ms, nt = m.stencil ? Ns : Hb);
          const U = { colorFormat: f.RGBA8, depthFormat: I, scaleFactor: r };
          s = this.getBinding(), d = s.createProjectionLayer(U), v.updateRenderState({ layers: [d] }), e.setPixelRatio(1), e.setSize(d.textureWidth, d.textureHeight, false), x = new ib(d.textureWidth, d.textureHeight, { format: W7, type: yz, depthTexture: new FK(d.textureWidth, d.textureHeight, nt, void 0, void 0, void 0, void 0, void 0, void 0, et), stencilBuffer: m.stencil, colorSpace: e.outputColorSpace, samples: m.antialias ? 4 : 0, resolveDepthBuffer: d.ignoreDepthValues === false, resolveStencilBuffer: d.ignoreDepthValues === false });
        } else {
          const et = { antialias: m.antialias, alpha: true, depth: m.depth, stencil: m.stencil, framebufferScaleFactor: r };
          c = new XRWebGLLayer(v, f, et), v.updateRenderState({ baseLayer: c }), e.setPixelRatio(1), e.setSize(c.framebufferWidth, c.framebufferHeight, false), x = new ib(c.framebufferWidth, c.framebufferHeight, { format: W7, type: yz, colorSpace: e.outputColorSpace, stencilBuffer: m.stencil, resolveDepthBuffer: c.ignoreDepthValues === false, resolveStencilBuffer: c.ignoreDepthValues === false });
        }
        x.isXRRenderTarget = true, this.setFoveation(b), a = null, z = await v.requestReferenceSpace(u), A.setContext(v), A.start(), n.isPresenting = true, n.dispatchEvent({ type: "sessionstart" });
      }
    }, this.getEnvironmentBlendMode = function() {
      if (v !== null) return v.environmentBlendMode;
    }, this.getDepthTexture = function() {
      return X.getDepthTexture();
    };
    function V(Q) {
      for (let B = 0; B < Q.removed.length; B++) {
        const et = Q.removed[B], nt = O.indexOf(et);
        nt >= 0 && (O[nt] = null, L[nt].disconnect(et));
      }
      for (let B = 0; B < Q.added.length; B++) {
        const et = Q.added[B];
        let nt = O.indexOf(et);
        if (nt === -1) {
          for (let U = 0; U < L.length; U++) if (U >= O.length) {
            O.push(et), nt = U;
            break;
          } else if (O[U] === null) {
            O[U] = et, nt = U;
            break;
          }
          if (nt === -1) break;
        }
        const I = L[nt];
        I && I.connect(et);
      }
    }
    const G = new ht(), R = new ht();
    function k(Q, B, et) {
      G.setFromMatrixPosition(B.matrixWorld), R.setFromMatrixPosition(et.matrixWorld);
      const nt = G.distanceTo(R), I = B.projectionMatrix.elements, U = et.projectionMatrix.elements, J = I[14] / (I[10] - 1), g = I[14] / (I[10] + 1), tt = (I[9] + 1) / I[5], zt = (I[9] - 1) / I[5], ut = (I[8] - 1) / I[0], Pt = (U[8] + 1) / U[0], xt = J * ut, Lt = J * Pt, Mt = nt / (-ut + Pt), Ht = Mt * -ut;
      if (B.matrixWorld.decompose(Q.position, Q.quaternion, Q.scale), Q.translateX(Ht), Q.translateZ(Mt), Q.matrixWorld.compose(Q.position, Q.quaternion, Q.scale), Q.matrixWorldInverse.copy(Q.matrixWorld).invert(), I[10] === -1) Q.projectionMatrix.copy(B.projectionMatrix), Q.projectionMatrixInverse.copy(B.projectionMatrixInverse);
      else {
        const Gt = J + Mt, ft = g + Mt, $ = xt - Ht, yt = Lt + (nt - Ht), Tt = tt * g / ft * Gt, Dt = zt * g / ft * Gt;
        Q.projectionMatrix.makePerspective($, yt, Tt, Dt, Gt, ft), Q.projectionMatrixInverse.copy(Q.projectionMatrix).invert();
      }
    }
    function K(Q, B) {
      B === null ? Q.matrixWorld.copy(Q.matrix) : Q.matrixWorld.multiplyMatrices(B.matrixWorld, Q.matrix), Q.matrixWorldInverse.copy(Q.matrixWorld).invert();
    }
    this.updateCamera = function(Q) {
      if (v === null) return;
      let B = Q.near, et = Q.far;
      X.texture !== null && (X.depthNear > 0 && (B = X.depthNear), X.depthFar > 0 && (et = X.depthFar)), T.near = M.near = H.near = B, T.far = M.far = H.far = et, (w !== T.near || Y !== T.far) && (v.updateRenderState({ depthNear: T.near, depthFar: T.far }), w = T.near, Y = T.far), T.layers.mask = Q.layers.mask | 6, H.layers.mask = T.layers.mask & 3, M.layers.mask = T.layers.mask & 5;
      const nt = Q.parent, I = T.cameras;
      K(T, nt);
      for (let U = 0; U < I.length; U++) K(I[U], nt);
      I.length === 2 ? k(T, H, M) : T.projectionMatrix.copy(H.projectionMatrix), _(Q, T, nt);
    };
    function _(Q, B, et) {
      et === null ? Q.matrix.copy(B.matrixWorld) : (Q.matrix.copy(et.matrixWorld), Q.matrix.invert(), Q.matrix.multiply(B.matrixWorld)), Q.matrix.decompose(Q.position, Q.quaternion, Q.scale), Q.updateMatrixWorld(true), Q.projectionMatrix.copy(B.projectionMatrix), Q.projectionMatrixInverse.copy(B.projectionMatrixInverse), Q.isPerspectiveCamera && (Q.fov = ps * 2 * Math.atan(1 / Q.projectionMatrix.elements[5]), Q.zoom = 1);
    }
    this.getCamera = function() {
      return T;
    }, this.getFoveation = function() {
      if (!(d === null && c === null)) return b;
    }, this.setFoveation = function(Q) {
      b = Q, d !== null && (d.fixedFoveation = Q), c !== null && c.fixedFoveation !== void 0 && (c.fixedFoveation = Q);
    }, this.hasDepthSensing = function() {
      return X.texture !== null;
    }, this.getDepthSensingMesh = function() {
      return X.getMesh(T);
    }, this.getCameraTexture = function(Q) {
      return y[Q];
    };
    let vt = null;
    function rt(Q, B) {
      if (P = B.getViewerPose(a || z), l = B, P !== null) {
        const et = P.views;
        c !== null && (e.setRenderTargetFramebuffer(x, c.framebuffer), e.setRenderTarget(x));
        let nt = false;
        et.length !== T.cameras.length && (T.cameras.length = 0, nt = true);
        for (let g = 0; g < et.length; g++) {
          const tt = et[g];
          let zt = null;
          if (c !== null) zt = c.getViewport(tt);
          else {
            const Pt = s.getViewSubImage(d, tt);
            zt = Pt.viewport, g === 0 && (e.setRenderTargetTextures(x, Pt.colorTexture, Pt.depthStencilTexture), e.setRenderTarget(x));
          }
          let ut = p[g];
          ut === void 0 && (ut = new qr(), ut.layers.enable(g), ut.viewport = new Un(), p[g] = ut), ut.matrix.fromArray(tt.transform.matrix), ut.matrix.decompose(ut.position, ut.quaternion, ut.scale), ut.projectionMatrix.fromArray(tt.projectionMatrix), ut.projectionMatrixInverse.copy(ut.projectionMatrix).invert(), ut.viewport.set(zt.x, zt.y, zt.width, zt.height), g === 0 && (T.matrix.copy(ut.matrix), T.matrix.decompose(T.position, T.quaternion, T.scale)), nt === true && T.cameras.push(ut);
        }
        const I = v.enabledFeatures;
        if (I && I.includes("depth-sensing") && v.depthUsage == "gpu-optimized" && W) {
          s = n.getBinding();
          const g = s.getDepthInformation(et[0]);
          g && g.isValid && g.texture && X.init(g, v.renderState);
        }
        if (I && I.includes("camera-access") && W) {
          e.state.unbindTexture(), s = n.getBinding();
          for (let g = 0; g < et.length; g++) {
            const tt = et[g].camera;
            if (tt) {
              let zt = y[tt];
              zt || (zt = new RK(), y[tt] = zt);
              const ut = s.getCameraImage(tt);
              zt.sourceTexture = ut;
            }
          }
        }
      }
      for (let et = 0; et < L.length; et++) {
        const nt = O[et], I = L[et];
        nt !== null && I !== void 0 && I.update(nt, B, a || z);
      }
      vt && vt(Q, B), B.detectedPlanes && n.dispatchEvent({ type: "planesdetected", data: B }), l = null;
    }
    const A = new uE();
    A.setAnimationLoop(rt), this.setAnimationLoop = function(Q) {
      vt = Q;
    }, this.dispose = function() {
    };
  }
}
const qb = new xz();
const WSt = new cn();
function ySt(t, e) {
  function f(X, y) {
    X.matrixAutoUpdate === true && X.updateMatrix(), y.value.copy(X.matrix);
  }
  function n(X, y) {
    y.color.getRGB(X.fogColor.value, mK(t)), y.isFog ? (X.fogNear.value = y.near, X.fogFar.value = y.far) : y.isFogExp2 && (X.fogDensity.value = y.density);
  }
  function v(X, y, m, j, x) {
    y.isMeshBasicMaterial || y.isMeshLambertMaterial ? r(X, y) : y.isMeshToonMaterial ? (r(X, y), s(X, y)) : y.isMeshPhongMaterial ? (r(X, y), P(X, y)) : y.isMeshStandardMaterial ? (r(X, y), d(X, y), y.isMeshPhysicalMaterial && c(X, y, x)) : y.isMeshMatcapMaterial ? (r(X, y), l(X, y)) : y.isMeshDepthMaterial ? r(X, y) : y.isMeshDistanceMaterial ? (r(X, y), W(X, y)) : y.isMeshNormalMaterial ? r(X, y) : y.isLineBasicMaterial ? (z(X, y), y.isLineDashedMaterial && u(X, y)) : y.isPointsMaterial ? b(X, y, m, j) : y.isSpriteMaterial ? a(X, y) : y.isShadowMaterial ? (X.color.value.copy(y.color), X.opacity.value = y.opacity) : y.isShaderMaterial && (y.uniformsNeedUpdate = false);
  }
  function r(X, y) {
    X.opacity.value = y.opacity, y.color && X.diffuse.value.copy(y.color), y.emissive && X.emissive.value.copy(y.emissive).multiplyScalar(y.emissiveIntensity), y.map && (X.map.value = y.map, f(y.map, X.mapTransform)), y.alphaMap && (X.alphaMap.value = y.alphaMap, f(y.alphaMap, X.alphaMapTransform)), y.bumpMap && (X.bumpMap.value = y.bumpMap, f(y.bumpMap, X.bumpMapTransform), X.bumpScale.value = y.bumpScale, y.side === Y3 && (X.bumpScale.value *= -1)), y.normalMap && (X.normalMap.value = y.normalMap, f(y.normalMap, X.normalMapTransform), X.normalScale.value.copy(y.normalScale), y.side === Y3 && X.normalScale.value.negate()), y.displacementMap && (X.displacementMap.value = y.displacementMap, f(y.displacementMap, X.displacementMapTransform), X.displacementScale.value = y.displacementScale, X.displacementBias.value = y.displacementBias), y.emissiveMap && (X.emissiveMap.value = y.emissiveMap, f(y.emissiveMap, X.emissiveMapTransform)), y.specularMap && (X.specularMap.value = y.specularMap, f(y.specularMap, X.specularMapTransform)), y.alphaTest > 0 && (X.alphaTest.value = y.alphaTest);
    const m = e.get(y), j = m.envMap, x = m.envMapRotation;
    j && (X.envMap.value = j, qb.copy(x), qb.x *= -1, qb.y *= -1, qb.z *= -1, j.isCubeTexture && j.isRenderTargetTexture === false && (qb.y *= -1, qb.z *= -1), X.envMapRotation.value.setFromMatrix4(WSt.makeRotationFromEuler(qb)), X.flipEnvMap.value = j.isCubeTexture && j.isRenderTargetTexture === false ? -1 : 1, X.reflectivity.value = y.reflectivity, X.ior.value = y.ior, X.refractionRatio.value = y.refractionRatio), y.lightMap && (X.lightMap.value = y.lightMap, X.lightMapIntensity.value = y.lightMapIntensity, f(y.lightMap, X.lightMapTransform)), y.aoMap && (X.aoMap.value = y.aoMap, X.aoMapIntensity.value = y.aoMapIntensity, f(y.aoMap, X.aoMapTransform));
  }
  function z(X, y) {
    X.diffuse.value.copy(y.color), X.opacity.value = y.opacity, y.map && (X.map.value = y.map, f(y.map, X.mapTransform));
  }
  function u(X, y) {
    X.dashSize.value = y.dashSize, X.totalSize.value = y.dashSize + y.gapSize, X.scale.value = y.scale;
  }
  function b(X, y, m, j) {
    X.diffuse.value.copy(y.color), X.opacity.value = y.opacity, X.size.value = y.size * m, X.scale.value = j * 0.5, y.map && (X.map.value = y.map, f(y.map, X.uvTransform)), y.alphaMap && (X.alphaMap.value = y.alphaMap, f(y.alphaMap, X.alphaMapTransform)), y.alphaTest > 0 && (X.alphaTest.value = y.alphaTest);
  }
  function a(X, y) {
    X.diffuse.value.copy(y.color), X.opacity.value = y.opacity, X.rotation.value = y.rotation, y.map && (X.map.value = y.map, f(y.map, X.mapTransform)), y.alphaMap && (X.alphaMap.value = y.alphaMap, f(y.alphaMap, X.alphaMapTransform)), y.alphaTest > 0 && (X.alphaTest.value = y.alphaTest);
  }
  function P(X, y) {
    X.specular.value.copy(y.specular), X.shininess.value = Math.max(y.shininess, 1e-4);
  }
  function s(X, y) {
    y.gradientMap && (X.gradientMap.value = y.gradientMap);
  }
  function d(X, y) {
    X.metalness.value = y.metalness, y.metalnessMap && (X.metalnessMap.value = y.metalnessMap, f(y.metalnessMap, X.metalnessMapTransform)), X.roughness.value = y.roughness, y.roughnessMap && (X.roughnessMap.value = y.roughnessMap, f(y.roughnessMap, X.roughnessMapTransform)), y.envMap && (X.envMapIntensity.value = y.envMapIntensity);
  }
  function c(X, y, m) {
    X.ior.value = y.ior, y.sheen > 0 && (X.sheenColor.value.copy(y.sheenColor).multiplyScalar(y.sheen), X.sheenRoughness.value = y.sheenRoughness, y.sheenColorMap && (X.sheenColorMap.value = y.sheenColorMap, f(y.sheenColorMap, X.sheenColorMapTransform)), y.sheenRoughnessMap && (X.sheenRoughnessMap.value = y.sheenRoughnessMap, f(y.sheenRoughnessMap, X.sheenRoughnessMapTransform))), y.clearcoat > 0 && (X.clearcoat.value = y.clearcoat, X.clearcoatRoughness.value = y.clearcoatRoughness, y.clearcoatMap && (X.clearcoatMap.value = y.clearcoatMap, f(y.clearcoatMap, X.clearcoatMapTransform)), y.clearcoatRoughnessMap && (X.clearcoatRoughnessMap.value = y.clearcoatRoughnessMap, f(y.clearcoatRoughnessMap, X.clearcoatRoughnessMapTransform)), y.clearcoatNormalMap && (X.clearcoatNormalMap.value = y.clearcoatNormalMap, f(y.clearcoatNormalMap, X.clearcoatNormalMapTransform), X.clearcoatNormalScale.value.copy(y.clearcoatNormalScale), y.side === Y3 && X.clearcoatNormalScale.value.negate())), y.dispersion > 0 && (X.dispersion.value = y.dispersion), y.iridescence > 0 && (X.iridescence.value = y.iridescence, X.iridescenceIOR.value = y.iridescenceIOR, X.iridescenceThicknessMinimum.value = y.iridescenceThicknessRange[0], X.iridescenceThicknessMaximum.value = y.iridescenceThicknessRange[1], y.iridescenceMap && (X.iridescenceMap.value = y.iridescenceMap, f(y.iridescenceMap, X.iridescenceMapTransform)), y.iridescenceThicknessMap && (X.iridescenceThicknessMap.value = y.iridescenceThicknessMap, f(y.iridescenceThicknessMap, X.iridescenceThicknessMapTransform))), y.transmission > 0 && (X.transmission.value = y.transmission, X.transmissionSamplerMap.value = m.texture, X.transmissionSamplerSize.value.set(m.width, m.height), y.transmissionMap && (X.transmissionMap.value = y.transmissionMap, f(y.transmissionMap, X.transmissionMapTransform)), X.thickness.value = y.thickness, y.thicknessMap && (X.thicknessMap.value = y.thicknessMap, f(y.thicknessMap, X.thicknessMapTransform)), X.attenuationDistance.value = y.attenuationDistance, X.attenuationColor.value.copy(y.attenuationColor)), y.anisotropy > 0 && (X.anisotropyVector.value.set(y.anisotropy * Math.cos(y.anisotropyRotation), y.anisotropy * Math.sin(y.anisotropyRotation)), y.anisotropyMap && (X.anisotropyMap.value = y.anisotropyMap, f(y.anisotropyMap, X.anisotropyMapTransform))), X.specularIntensity.value = y.specularIntensity, X.specularColor.value.copy(y.specularColor), y.specularColorMap && (X.specularColorMap.value = y.specularColorMap, f(y.specularColorMap, X.specularColorMapTransform)), y.specularIntensityMap && (X.specularIntensityMap.value = y.specularIntensityMap, f(y.specularIntensityMap, X.specularIntensityMapTransform));
  }
  function l(X, y) {
    y.matcap && (X.matcap.value = y.matcap);
  }
  function W(X, y) {
    const m = e.get(y).light;
    X.referencePosition.value.setFromMatrixPosition(m.matrixWorld), X.nearDistance.value = m.shadow.camera.near, X.farDistance.value = m.shadow.camera.far;
  }
  return { refreshFogUniforms: n, refreshMaterialUniforms: v };
}
function ZSt(t, e, f, n) {
  let v = {}, r = {}, z = [];
  const u = t.getParameter(t.MAX_UNIFORM_BUFFER_BINDINGS);
  function b(m, j) {
    const x = j.program;
    n.uniformBlockBinding(m, x);
  }
  function a(m, j) {
    let x = v[m.id];
    x === void 0 && (l(m), x = P(m), v[m.id] = x, m.addEventListener("dispose", X));
    const L = j.program;
    n.updateUBOMapping(m, L);
    const O = e.render.frame;
    r[m.id] !== O && (d(m), r[m.id] = O);
  }
  function P(m) {
    const j = s();
    m.__bindingPointIndex = j;
    const x = t.createBuffer(), L = m.__size, O = m.usage;
    return t.bindBuffer(t.UNIFORM_BUFFER, x), t.bufferData(t.UNIFORM_BUFFER, L, O), t.bindBuffer(t.UNIFORM_BUFFER, null), t.bindBufferBase(t.UNIFORM_BUFFER, j, x), x;
  }
  function s() {
    for (let m = 0; m < u; m++) if (z.indexOf(m) === -1) return z.push(m), m;
    return console.error("THREE.WebGLRenderer: Maximum number of simultaneously usable uniforms groups reached."), 0;
  }
  function d(m) {
    const j = v[m.id], x = m.uniforms, L = m.__cache;
    t.bindBuffer(t.UNIFORM_BUFFER, j);
    for (let O = 0, N = x.length; O < N; O++) {
      const i = Array.isArray(x[O]) ? x[O] : [x[O]];
      for (let H = 0, M = i.length; H < M; H++) {
        const p = i[H];
        if (c(p, O, H, L) === true) {
          const T = p.__offset, w = Array.isArray(p.value) ? p.value : [p.value];
          let Y = 0;
          for (let q = 0; q < w.length; q++) {
            const D = w[q], V = W(D);
            typeof D == "number" || typeof D == "boolean" ? (p.__data[0] = D, t.bufferSubData(t.UNIFORM_BUFFER, T + Y, p.__data)) : D.isMatrix3 ? (p.__data[0] = D.elements[0], p.__data[1] = D.elements[1], p.__data[2] = D.elements[2], p.__data[3] = 0, p.__data[4] = D.elements[3], p.__data[5] = D.elements[4], p.__data[6] = D.elements[5], p.__data[7] = 0, p.__data[8] = D.elements[6], p.__data[9] = D.elements[7], p.__data[10] = D.elements[8], p.__data[11] = 0) : (D.toArray(p.__data, Y), Y += V.storage / Float32Array.BYTES_PER_ELEMENT);
          }
          t.bufferSubData(t.UNIFORM_BUFFER, T, p.__data);
        }
      }
    }
    t.bindBuffer(t.UNIFORM_BUFFER, null);
  }
  function c(m, j, x, L) {
    const O = m.value, N = j + "_" + x;
    if (L[N] === void 0) return typeof O == "number" || typeof O == "boolean" ? L[N] = O : L[N] = O.clone(), true;
    {
      const i = L[N];
      if (typeof O == "number" || typeof O == "boolean") {
        if (i !== O) return L[N] = O, true;
      } else if (i.equals(O) === false) return i.copy(O), true;
    }
    return false;
  }
  function l(m) {
    const j = m.uniforms;
    let x = 0;
    const L = 16;
    for (let N = 0, i = j.length; N < i; N++) {
      const H = Array.isArray(j[N]) ? j[N] : [j[N]];
      for (let M = 0, p = H.length; M < p; M++) {
        const T = H[M], w = Array.isArray(T.value) ? T.value : [T.value];
        for (let Y = 0, q = w.length; Y < q; Y++) {
          const D = w[Y], V = W(D), G = x % L, R = G % V.boundary, k = G + R;
          x += R, k !== 0 && L - k < V.storage && (x += L - k), T.__data = new Float32Array(V.storage / Float32Array.BYTES_PER_ELEMENT), T.__offset = x, x += V.storage;
        }
      }
    }
    const O = x % L;
    return O > 0 && (x += L - O), m.__size = x, m.__cache = {}, this;
  }
  function W(m) {
    const j = { boundary: 0, storage: 0 };
    return typeof m == "number" || typeof m == "boolean" ? (j.boundary = 4, j.storage = 4) : m.isVector2 ? (j.boundary = 8, j.storage = 8) : m.isVector3 || m.isColor ? (j.boundary = 16, j.storage = 12) : m.isVector4 ? (j.boundary = 16, j.storage = 16) : m.isMatrix3 ? (j.boundary = 48, j.storage = 48) : m.isMatrix4 ? (j.boundary = 64, j.storage = 64) : m.isTexture ? console.warn("THREE.WebGLRenderer: Texture samplers can not be part of an uniforms group.") : console.warn("THREE.WebGLRenderer: Unsupported uniform value type.", m), j;
  }
  function X(m) {
    const j = m.target;
    j.removeEventListener("dispose", X);
    const x = z.indexOf(j.__bindingPointIndex);
    z.splice(x, 1), t.deleteBuffer(v[j.id]), delete v[j.id], delete r[j.id];
  }
  function y() {
    for (const m in v) t.deleteBuffer(v[m]);
    z = [], v = {}, r = {};
  }
  return { bind: b, update: a, dispose: y };
}
class mSt {
  constructor(e = {}) {
    const { canvas: f = Kqt(), context: n = null, depth: v = true, stencil: r = false, alpha: z = false, antialias: u = false, premultipliedAlpha: b = true, preserveDrawingBuffer: a = false, powerPreference: P = "default", failIfMajorPerformanceCaveat: s = false, reversedDepthBuffer: d = false } = e;
    this.isWebGLRenderer = true;
    let c;
    if (n !== null) {
      if (typeof WebGLRenderingContext < "u" && n instanceof WebGLRenderingContext) throw new Error("THREE.WebGLRenderer: WebGL 1 is not supported since r163.");
      c = n.getContextAttributes().alpha;
    } else c = z;
    const l = new Uint32Array(4), W = new Int32Array(4);
    let X = null, y = null;
    const m = [], j = [];
    this.domElement = f, this.debug = { checkShaderErrors: true, onShaderError: null }, this.autoClear = true, this.autoClearColor = true, this.autoClearDepth = true, this.autoClearStencil = true, this.sortObjects = true, this.clippingPlanes = [], this.localClippingEnabled = false, this.toneMapping = p5, this.toneMappingExposure = 1, this.transmissionResolutionScale = 1;
    const x = this;
    let L = false;
    this._outputColorSpace = Qv;
    let O = 0, N = 0, i = null, H = -1, M = null;
    const p = new Un(), T = new Un();
    let w = null;
    const Y = new Nf(0);
    let q = 0, D = f.width, V = f.height, G = 1, R = null, k = null;
    const K = new Un(0, 0, D, V), _ = new Un(0, 0, D, V);
    let vt = false;
    const rt = new MM();
    let A = false, Q = false;
    const B = new cn(), et = new ht(), nt = new Un(), I = { background: null, fog: null, environment: null, overrideMaterial: null, isScene: true };
    let U = false;
    function J() {
      return i === null ? G : 1;
    }
    let g = n;
    function tt(st, Vt) {
      return f.getContext(st, Vt);
    }
    try {
      const st = { alpha: true, depth: v, stencil: r, antialias: u, premultipliedAlpha: b, preserveDrawingBuffer: a, powerPreference: P, failIfMajorPerformanceCaveat: s };
      if ("setAttribute" in f && f.setAttribute("data-engine", `three.js r${vN}`), f.addEventListener("webglcontextlost", Ut, false), f.addEventListener("webglcontextrestored", te, false), f.addEventListener("webglcontextcreationerror", It, false), g === null) {
        const Vt = "webgl2";
        if (g = tt(Vt, st), g === null) throw tt(Vt) ? new Error("Error creating WebGL context with your selected attributes.") : new Error("Error creating WebGL context.");
      }
    } catch (st) {
      throw console.error("THREE.WebGLRenderer: " + st.message), st;
    }
    let zt, ut, Pt, xt, Lt, Mt, Ht, Gt, ft, $, yt, Tt, Dt, pt, ce, ee, xe, me, ne, Le, wt, it, Rt, jt;
    function Xt() {
      zt = new TJt(g), zt.init(), it = new sSt(g, zt), ut = new LJt(g, zt, e, it), Pt = new aSt(g, zt), ut.reversedDepthBuffer && d && Pt.buffers.depth.setReversed(true), xt = new hJt(g), Lt = new Agt(), Mt = new PSt(g, zt, Pt, Lt, ut, it, xt), Ht = new HJt(x), Gt = new pJt(x), ft = new ZRt(g), Rt = new jJt(g, ft), $ = new oJt(g, ft, xt, Rt), yt = new VJt(g, $, ft, xt), ne = new GJt(g, ut, Mt), ee = new OJt(Lt), Tt = new Cgt(x, Ht, Gt, zt, ut, Rt, ee), Dt = new ySt(x, Lt), pt = new _gt(), ce = new vSt(zt), me = new mJt(x, Ht, Gt, Pt, yt, c, b), xe = new uSt(x, yt, ut), jt = new ZSt(g, xt, ut, Pt), Le = new xJt(g, zt, xt), wt = new wJt(g, zt, xt), xt.programs = Tt.programs, x.capabilities = ut, x.extensions = zt, x.properties = Lt, x.renderLists = pt, x.shadowMap = xe, x.state = Pt, x.info = xt;
    }
    Xt();
    const ot = new XSt(x, g);
    this.xr = ot, this.getContext = function() {
      return g;
    }, this.getContextAttributes = function() {
      return g.getContextAttributes();
    }, this.forceContextLoss = function() {
      const st = zt.get("WEBGL_lose_context");
      st && st.loseContext();
    }, this.forceContextRestore = function() {
      const st = zt.get("WEBGL_lose_context");
      st && st.restoreContext();
    }, this.getPixelRatio = function() {
      return G;
    }, this.setPixelRatio = function(st) {
      st !== void 0 && (G = st, this.setSize(D, V, false));
    }, this.getSize = function(st) {
      return st.set(D, V);
    }, this.setSize = function(st, Vt, Ct = true) {
      if (ot.isPresenting) {
        console.warn("THREE.WebGLRenderer: Can't change size while VR device is presenting.");
        return;
      }
      D = st, V = Vt, f.width = Math.floor(st * G), f.height = Math.floor(Vt * G), Ct === true && (f.style.width = st + "px", f.style.height = Vt + "px"), this.setViewport(0, 0, st, Vt);
    }, this.getDrawingBufferSize = function(st) {
      return st.set(D * G, V * G).floor();
    }, this.setDrawingBufferSize = function(st, Vt, Ct) {
      D = st, V = Vt, G = Ct, f.width = Math.floor(st * Ct), f.height = Math.floor(Vt * Ct), this.setViewport(0, 0, st, Vt);
    }, this.getCurrentViewport = function(st) {
      return st.copy(p);
    }, this.getViewport = function(st) {
      return st.copy(K);
    }, this.setViewport = function(st, Vt, Ct, At) {
      st.isVector4 ? K.set(st.x, st.y, st.z, st.w) : K.set(st, Vt, Ct, At), Pt.viewport(p.copy(K).multiplyScalar(G).round());
    }, this.getScissor = function(st) {
      return st.copy(_);
    }, this.setScissor = function(st, Vt, Ct, At) {
      st.isVector4 ? _.set(st.x, st.y, st.z, st.w) : _.set(st, Vt, Ct, At), Pt.scissor(T.copy(_).multiplyScalar(G).round());
    }, this.getScissorTest = function() {
      return vt;
    }, this.setScissorTest = function(st) {
      Pt.setScissorTest(vt = st);
    }, this.setOpaqueSort = function(st) {
      R = st;
    }, this.setTransparentSort = function(st) {
      k = st;
    }, this.getClearColor = function(st) {
      return st.copy(me.getClearColor());
    }, this.setClearColor = function() {
      me.setClearColor(...arguments);
    }, this.getClearAlpha = function() {
      return me.getClearAlpha();
    }, this.setClearAlpha = function() {
      me.setClearAlpha(...arguments);
    }, this.clear = function(st = true, Vt = true, Ct = true) {
      let At = 0;
      if (st) {
        let Jt = false;
        if (i !== null) {
          const ie = i.texture.format;
          Jt = ie === ON || ie === LN || ie === xN;
        }
        if (Jt) {
          const ie = i.texture.type, ke = ie === yz || ie === Hb || ie === Hs || ie === Ns || ie === ZN || ie === mN, Ce = me.getClearColor(), Ke = me.getClearAlpha(), Yt = Ce.r, Pe = Ce.g, Ze = Ce.b;
          ke ? (l[0] = Yt, l[1] = Pe, l[2] = Ze, l[3] = Ke, g.clearBufferuiv(g.COLOR, 0, l)) : (W[0] = Yt, W[1] = Pe, W[2] = Ze, W[3] = Ke, g.clearBufferiv(g.COLOR, 0, W));
        } else At |= g.COLOR_BUFFER_BIT;
      }
      Vt && (At |= g.DEPTH_BUFFER_BIT), Ct && (At |= g.STENCIL_BUFFER_BIT, this.state.buffers.stencil.setMask(4294967295)), g.clear(At);
    }, this.clearColor = function() {
      this.clear(true, false, false);
    }, this.clearDepth = function() {
      this.clear(false, true, false);
    }, this.clearStencil = function() {
      this.clear(false, false, true);
    }, this.dispose = function() {
      f.removeEventListener("webglcontextlost", Ut, false), f.removeEventListener("webglcontextrestored", te, false), f.removeEventListener("webglcontextcreationerror", It, false), me.dispose(), pt.dispose(), ce.dispose(), Lt.dispose(), Ht.dispose(), Gt.dispose(), yt.dispose(), Rt.dispose(), jt.dispose(), Tt.dispose(), ot.dispose(), ot.removeEventListener("sessionstart", Oe), ot.removeEventListener("sessionend", gt), ze.stop();
    };
    function Ut(st) {
      st.preventDefault(), console.log("THREE.WebGLRenderer: Context Lost."), L = true;
    }
    function te() {
      console.log("THREE.WebGLRenderer: Context Restored."), L = false;
      const st = xt.autoReset, Vt = xe.enabled, Ct = xe.autoUpdate, At = xe.needsUpdate, Jt = xe.type;
      Xt(), xt.autoReset = st, xe.enabled = Vt, xe.autoUpdate = Ct, xe.needsUpdate = At, xe.type = Jt;
    }
    function It(st) {
      console.error("THREE.WebGLRenderer: A WebGL context could not be created. Reason: ", st.statusMessage);
    }
    function Bt(st) {
      const Vt = st.target;
      Vt.removeEventListener("dispose", Bt), de(Vt);
    }
    function de(st) {
      ve(st), Lt.remove(st);
    }
    function ve(st) {
      const Vt = Lt.get(st).programs;
      Vt !== void 0 && (Vt.forEach(function(Ct) {
        Tt.releaseProgram(Ct);
      }), st.isShaderMaterial && Tt.releaseShaderCache(st));
    }
    this.renderBufferDirect = function(st, Vt, Ct, At, Jt, ie) {
      Vt === null && (Vt = I);
      const ke = Jt.isMesh && Jt.matrixWorld.determinant() < 0, Ce = Nz(st, Vt, Ct, At, Jt);
      Pt.setMaterial(At, ke);
      let Ke = Ct.index, Yt = 1;
      if (At.wireframe === true) {
        if (Ke = $.getWireframeAttribute(Ct), Ke === void 0) return;
        Yt = 2;
      }
      const Pe = Ct.drawRange, Ze = Ct.attributes.position;
      let pe = Pe.start * Yt, Xf = (Pe.start + Pe.count) * Yt;
      ie !== null && (pe = Math.max(pe, ie.start * Yt), Xf = Math.min(Xf, (ie.start + ie.count) * Yt)), Ke !== null ? (pe = Math.max(pe, 0), Xf = Math.min(Xf, Ke.count)) : Ze != null && (pe = Math.max(pe, 0), Xf = Math.min(Xf, Ze.count));
      const Vf = Xf - pe;
      if (Vf < 0 || Vf === 1 / 0) return;
      Rt.setup(Jt, At, Ce, Ct, Ke);
      let Qf, bn = Le;
      if (Ke !== null && (Qf = ft.get(Ke), bn = wt, bn.setIndex(Qf)), Jt.isMesh) At.wireframe === true ? (Pt.setLineWidth(At.wireframeLinewidth * J()), bn.setMode(g.LINES)) : bn.setMode(g.TRIANGLES);
      else if (Jt.isLine) {
        let uf = At.linewidth;
        uf === void 0 && (uf = 1), Pt.setLineWidth(uf * J()), Jt.isLineSegments ? bn.setMode(g.LINES) : Jt.isLineLoop ? bn.setMode(g.LINE_LOOP) : bn.setMode(g.LINE_STRIP);
      } else Jt.isPoints ? bn.setMode(g.POINTS) : Jt.isSprite && bn.setMode(g.TRIANGLES);
      if (Jt.isBatchedMesh) if (Jt._multiDrawInstances !== null) os("THREE.WebGLRenderer: renderMultiDrawInstances has been deprecated and will be removed in r184. Append to renderMultiDraw arguments and use indirection."), bn.renderMultiDrawInstances(Jt._multiDrawStarts, Jt._multiDrawCounts, Jt._multiDrawCount, Jt._multiDrawInstances);
      else if (zt.get("WEBGL_multi_draw")) bn.renderMultiDraw(Jt._multiDrawStarts, Jt._multiDrawCounts, Jt._multiDrawCount);
      else {
        const uf = Jt._multiDrawStarts, hn = Jt._multiDrawCounts, If = Jt._multiDrawCount, vr = Ke ? ft.get(Ke).bytesPerElement : 1, m1 = Lt.get(At).currentProgram.getUniforms();
        for (let rr = 0; rr < If; rr++) m1.setValue(g, "_gl_DrawID", rr), bn.render(uf[rr] / vr, hn[rr]);
      }
      else if (Jt.isInstancedMesh) bn.renderInstances(pe, Vf, Jt.count);
      else if (Ct.isInstancedBufferGeometry) {
        const uf = Ct._maxInstanceCount !== void 0 ? Ct._maxInstanceCount : 1 / 0, hn = Math.min(Ct.instanceCount, uf);
        bn.renderInstances(pe, Vf, hn);
      } else bn.render(pe, Vf);
    };
    function Re(st, Vt, Ct) {
      st.transparent === true && st.side === $3 && st.forceSinglePass === false ? (st.side = Y3, st.needsUpdate = true, _n(st, Vt, Ct), st.side = M5, st.needsUpdate = true, _n(st, Vt, Ct), st.side = $3) : _n(st, Vt, Ct);
    }
    this.compile = function(st, Vt, Ct = null) {
      Ct === null && (Ct = st), y = ce.get(Ct), y.init(Vt), j.push(y), Ct.traverseVisible(function(Jt) {
        Jt.isLight && Jt.layers.test(Vt.layers) && (y.pushLight(Jt), Jt.castShadow && y.pushShadow(Jt));
      }), st !== Ct && st.traverseVisible(function(Jt) {
        Jt.isLight && Jt.layers.test(Vt.layers) && (y.pushLight(Jt), Jt.castShadow && y.pushShadow(Jt));
      }), y.setupLights();
      const At = /* @__PURE__ */ new Set();
      return st.traverse(function(Jt) {
        if (!(Jt.isMesh || Jt.isPoints || Jt.isLine || Jt.isSprite)) return;
        const ie = Jt.material;
        if (ie) if (Array.isArray(ie)) for (let ke = 0; ke < ie.length; ke++) {
          const Ce = ie[ke];
          Re(Ce, Ct, Jt), At.add(Ce);
        }
        else Re(ie, Ct, Jt), At.add(ie);
      }), y = j.pop(), At;
    }, this.compileAsync = function(st, Vt, Ct = null) {
      const At = this.compile(st, Vt, Ct);
      return new Promise((Jt) => {
        function ie() {
          if (At.forEach(function(ke) {
            Lt.get(ke).currentProgram.isReady() && At.delete(ke);
          }), At.size === 0) {
            Jt(st);
            return;
          }
          setTimeout(ie, 10);
        }
        zt.get("KHR_parallel_shader_compile") !== null ? ie() : setTimeout(ie, 10);
      });
    };
    let Qt = null;
    function be(st) {
      Qt && Qt(st);
    }
    function Oe() {
      ze.stop();
    }
    function gt() {
      ze.start();
    }
    const ze = new uE();
    ze.setAnimationLoop(be), typeof self < "u" && ze.setContext(self), this.setAnimationLoop = function(st) {
      Qt = st, ot.setAnimationLoop(st), st === null ? ze.stop() : ze.start();
    }, ot.addEventListener("sessionstart", Oe), ot.addEventListener("sessionend", gt), this.render = function(st, Vt) {
      if (Vt !== void 0 && Vt.isCamera !== true) {
        console.error("THREE.WebGLRenderer.render: camera is not an instance of THREE.Camera.");
        return;
      }
      if (L === true) return;
      if (st.matrixWorldAutoUpdate === true && st.updateMatrixWorld(), Vt.parent === null && Vt.matrixWorldAutoUpdate === true && Vt.updateMatrixWorld(), ot.enabled === true && ot.isPresenting === true && (ot.cameraAutoUpdate === true && ot.updateCamera(Vt), Vt = ot.getCamera()), st.isScene === true && st.onBeforeRender(x, st, Vt, i), y = ce.get(st, j.length), y.init(Vt), j.push(y), B.multiplyMatrices(Vt.projectionMatrix, Vt.matrixWorldInverse), rt.setFromProjectionMatrix(B, mz, Vt.reversedDepth), Q = this.localClippingEnabled, A = ee.init(this.clippingPlanes, Q), X = pt.get(st, m.length), X.init(), m.push(X), ot.enabled === true && ot.isPresenting === true) {
        const ie = x.xr.getDepthSensingMesh();
        ie !== null && ue(ie, Vt, -1 / 0, x.sortObjects);
      }
      ue(st, Vt, 0, x.sortObjects), X.finish(), x.sortObjects === true && X.sort(R, k), U = ot.enabled === false || ot.isPresenting === false || ot.hasDepthSensing() === false, U && me.addToRenderList(X, st), this.info.render.frame++, A === true && ee.beginShadows();
      const Ct = y.state.shadowsArray;
      xe.render(Ct, st, Vt), A === true && ee.endShadows(), this.info.autoReset === true && this.info.reset();
      const At = X.opaque, Jt = X.transmissive;
      if (y.setupLights(), Vt.isArrayCamera) {
        const ie = Vt.cameras;
        if (Jt.length > 0) for (let ke = 0, Ce = ie.length; ke < Ce; ke++) {
          const Ke = ie[ke];
          sf(At, Jt, st, Ke);
        }
        U && me.render(st);
        for (let ke = 0, Ce = ie.length; ke < Ce; ke++) {
          const Ke = ie[ke];
          nf(X, st, Ke, Ke.viewport);
        }
      } else Jt.length > 0 && sf(At, Jt, st, Vt), U && me.render(st), nf(X, st, Vt);
      i !== null && N === 0 && (Mt.updateMultisampleRenderTarget(i), Mt.updateRenderTargetMipmap(i)), st.isScene === true && st.onAfterRender(x, st, Vt), Rt.resetDefaultState(), H = -1, M = null, j.pop(), j.length > 0 ? (y = j[j.length - 1], A === true && ee.setGlobalState(x.clippingPlanes, y.state.camera)) : y = null, m.pop(), m.length > 0 ? X = m[m.length - 1] : X = null;
    };
    function ue(st, Vt, Ct, At) {
      if (st.visible === false) return;
      if (st.layers.test(Vt.layers)) {
        if (st.isGroup) Ct = st.renderOrder;
        else if (st.isLOD) st.autoUpdate === true && st.update(Vt);
        else if (st.isLight) y.pushLight(st), st.castShadow && y.pushShadow(st);
        else if (st.isSprite) {
          if (!st.frustumCulled || rt.intersectsSprite(st)) {
            At && nt.setFromMatrixPosition(st.matrixWorld).applyMatrix4(B);
            const ke = yt.update(st), Ce = st.material;
            Ce.visible && X.push(st, ke, Ce, Ct, nt.z, null);
          }
        } else if ((st.isMesh || st.isLine || st.isPoints) && (!st.frustumCulled || rt.intersectsObject(st))) {
          const ke = yt.update(st), Ce = st.material;
          if (At && (st.boundingSphere !== void 0 ? (st.boundingSphere === null && st.computeBoundingSphere(), nt.copy(st.boundingSphere.center)) : (ke.boundingSphere === null && ke.computeBoundingSphere(), nt.copy(ke.boundingSphere.center)), nt.applyMatrix4(st.matrixWorld).applyMatrix4(B)), Array.isArray(Ce)) {
            const Ke = ke.groups;
            for (let Yt = 0, Pe = Ke.length; Yt < Pe; Yt++) {
              const Ze = Ke[Yt], pe = Ce[Ze.materialIndex];
              pe && pe.visible && X.push(st, ke, pe, Ct, nt.z, Ze);
            }
          } else Ce.visible && X.push(st, ke, Ce, Ct, nt.z, null);
        }
      }
      const ie = st.children;
      for (let ke = 0, Ce = ie.length; ke < Ce; ke++) ue(ie[ke], Vt, Ct, At);
    }
    function nf(st, Vt, Ct, At) {
      const Jt = st.opaque, ie = st.transmissive, ke = st.transparent;
      y.setupLightsView(Ct), A === true && ee.setGlobalState(x.clippingPlanes, Ct), At && Pt.viewport(p.copy(At)), Jt.length > 0 && Of(Jt, Vt, Ct), ie.length > 0 && Of(ie, Vt, Ct), ke.length > 0 && Of(ke, Vt, Ct), Pt.buffers.depth.setTest(true), Pt.buffers.depth.setMask(true), Pt.buffers.color.setMask(true), Pt.setPolygonOffset(false);
    }
    function sf(st, Vt, Ct, At) {
      if ((Ct.isScene === true ? Ct.overrideMaterial : null) !== null) return;
      y.state.transmissionRenderTarget[At.id] === void 0 && (y.state.transmissionRenderTarget[At.id] = new ib(1, 1, { generateMipmaps: true, type: zt.has("EXT_color_buffer_half_float") || zt.has("EXT_color_buffer_float") ? Ba : yz, minFilter: Ob, samples: 4, stencilBuffer: r, resolveDepthBuffer: false, resolveStencilBuffer: false, colorSpace: Uf.workingColorSpace }));
      const ie = y.state.transmissionRenderTarget[At.id], ke = At.viewport || p;
      ie.setSize(ke.z * x.transmissionResolutionScale, ke.w * x.transmissionResolutionScale);
      const Ce = x.getRenderTarget(), Ke = x.getActiveCubeFace(), Yt = x.getActiveMipmapLevel();
      x.setRenderTarget(ie), x.getClearColor(Y), q = x.getClearAlpha(), q < 1 && x.setClearColor(16777215, 0.5), x.clear(), U && me.render(Ct);
      const Pe = x.toneMapping;
      x.toneMapping = p5;
      const Ze = At.viewport;
      if (At.viewport !== void 0 && (At.viewport = void 0), y.setupLightsView(At), A === true && ee.setGlobalState(x.clippingPlanes, At), Of(st, Ct, At), Mt.updateMultisampleRenderTarget(ie), Mt.updateRenderTargetMipmap(ie), zt.has("WEBGL_multisampled_render_to_texture") === false) {
        let pe = false;
        for (let Xf = 0, Vf = Vt.length; Xf < Vf; Xf++) {
          const Qf = Vt[Xf], bn = Qf.object, uf = Qf.geometry, hn = Qf.material, If = Qf.group;
          if (hn.side === $3 && bn.layers.test(At.layers)) {
            const vr = hn.side;
            hn.side = Y3, hn.needsUpdate = true, qf(bn, Ct, At, uf, hn, If), hn.side = vr, hn.needsUpdate = true, pe = true;
          }
        }
        pe === true && (Mt.updateMultisampleRenderTarget(ie), Mt.updateRenderTargetMipmap(ie));
      }
      x.setRenderTarget(Ce, Ke, Yt), x.setClearColor(Y, q), Ze !== void 0 && (At.viewport = Ze), x.toneMapping = Pe;
    }
    function Of(st, Vt, Ct) {
      const At = Vt.isScene === true ? Vt.overrideMaterial : null;
      for (let Jt = 0, ie = st.length; Jt < ie; Jt++) {
        const ke = st[Jt], Ce = ke.object, Ke = ke.geometry, Yt = ke.group;
        let Pe = ke.material;
        Pe.allowOverride === true && At !== null && (Pe = At), Ce.layers.test(Ct.layers) && qf(Ce, Vt, Ct, Ke, Pe, Yt);
      }
    }
    function qf(st, Vt, Ct, At, Jt, ie) {
      st.onBeforeRender(x, Vt, Ct, At, Jt, ie), st.modelViewMatrix.multiplyMatrices(Ct.matrixWorldInverse, st.matrixWorld), st.normalMatrix.getNormalMatrix(st.modelViewMatrix), Jt.onBeforeRender(x, Vt, Ct, At, st, ie), Jt.transparent === true && Jt.side === $3 && Jt.forceSinglePass === false ? (Jt.side = Y3, Jt.needsUpdate = true, x.renderBufferDirect(Ct, Vt, At, Jt, st, ie), Jt.side = M5, Jt.needsUpdate = true, x.renderBufferDirect(Ct, Vt, At, Jt, st, ie), Jt.side = $3) : x.renderBufferDirect(Ct, Vt, At, Jt, st, ie), st.onAfterRender(x, Vt, Ct, At, Jt, ie);
    }
    function _n(st, Vt, Ct) {
      Vt.isScene !== true && (Vt = I);
      const At = Lt.get(st), Jt = y.state.lights, ie = y.state.shadowsArray, ke = Jt.state.version, Ce = Tt.getParameters(st, Jt.state, ie, Vt, Ct), Ke = Tt.getProgramCacheKey(Ce);
      let Yt = At.programs;
      At.environment = st.isMeshStandardMaterial ? Vt.environment : null, At.fog = Vt.fog, At.envMap = (st.isMeshStandardMaterial ? Gt : Ht).get(st.envMap || At.environment), At.envMapRotation = At.environment !== null && st.envMap === null ? Vt.environmentRotation : st.envMapRotation, Yt === void 0 && (st.addEventListener("dispose", Bt), Yt = /* @__PURE__ */ new Map(), At.programs = Yt);
      let Pe = Yt.get(Ke);
      if (Pe !== void 0) {
        if (At.currentProgram === Pe && At.lightsStateVersion === ke) return O7(st, Ce), Pe;
      } else Ce.uniforms = Tt.getUniforms(st), st.onBeforeCompile(Ce, x), Pe = Tt.acquireProgram(Ce, Ke), Yt.set(Ke, Pe), At.uniforms = Ce.uniforms;
      const Ze = At.uniforms;
      return (!st.isShaderMaterial && !st.isRawShaderMaterial || st.clipping === true) && (Ze.clippingPlanes = ee.uniform), O7(st, Ce), At.needsLights = H7(st), At.lightsStateVersion = ke, At.needsLights && (Ze.ambientLightColor.value = Jt.state.ambient, Ze.lightProbe.value = Jt.state.probe, Ze.directionalLights.value = Jt.state.directional, Ze.directionalLightShadows.value = Jt.state.directionalShadow, Ze.spotLights.value = Jt.state.spot, Ze.spotLightShadows.value = Jt.state.spotShadow, Ze.rectAreaLights.value = Jt.state.rectArea, Ze.ltc_1.value = Jt.state.rectAreaLTC1, Ze.ltc_2.value = Jt.state.rectAreaLTC2, Ze.pointLights.value = Jt.state.point, Ze.pointLightShadows.value = Jt.state.pointShadow, Ze.hemisphereLights.value = Jt.state.hemi, Ze.directionalShadowMap.value = Jt.state.directionalShadowMap, Ze.directionalShadowMatrix.value = Jt.state.directionalShadowMatrix, Ze.spotShadowMap.value = Jt.state.spotShadowMap, Ze.spotLightMatrix.value = Jt.state.spotLightMatrix, Ze.spotLightMap.value = Jt.state.spotLightMap, Ze.pointShadowMap.value = Jt.state.pointShadowMap, Ze.pointShadowMatrix.value = Jt.state.pointShadowMatrix), At.currentProgram = Pe, At.uniformsList = null, Pe;
    }
    function L7(st) {
      if (st.uniformsList === null) {
        const Vt = st.currentProgram.getUniforms();
        st.uniformsList = A6.seqWithValue(Vt.seq, st.uniforms);
      }
      return st.uniformsList;
    }
    function O7(st, Vt) {
      const Ct = Lt.get(st);
      Ct.outputColorSpace = Vt.outputColorSpace, Ct.batching = Vt.batching, Ct.batchingColor = Vt.batchingColor, Ct.instancing = Vt.instancing, Ct.instancingColor = Vt.instancingColor, Ct.instancingMorph = Vt.instancingMorph, Ct.skinning = Vt.skinning, Ct.morphTargets = Vt.morphTargets, Ct.morphNormals = Vt.morphNormals, Ct.morphColors = Vt.morphColors, Ct.morphTargetsCount = Vt.morphTargetsCount, Ct.numClippingPlanes = Vt.numClippingPlanes, Ct.numIntersection = Vt.numClipIntersection, Ct.vertexAlphas = Vt.vertexAlphas, Ct.vertexTangents = Vt.vertexTangents, Ct.toneMapping = Vt.toneMapping;
    }
    function Nz(st, Vt, Ct, At, Jt) {
      Vt.isScene !== true && (Vt = I), Mt.resetTextureUnits();
      const ie = Vt.fog, ke = At.isMeshStandardMaterial ? Vt.environment : null, Ce = i === null ? x.outputColorSpace : i.isXRRenderTarget === true ? i.texture.colorSpace : Ua, Ke = (At.isMeshStandardMaterial ? Gt : Ht).get(At.envMap || ke), Yt = At.vertexColors === true && !!Ct.attributes.color && Ct.attributes.color.itemSize === 4, Pe = !!Ct.attributes.tangent && (!!At.normalMap || At.anisotropy > 0), Ze = !!Ct.morphAttributes.position, pe = !!Ct.morphAttributes.normal, Xf = !!Ct.morphAttributes.color;
      let Vf = p5;
      At.toneMapped && (i === null || i.isXRRenderTarget === true) && (Vf = x.toneMapping);
      const Qf = Ct.morphAttributes.position || Ct.morphAttributes.normal || Ct.morphAttributes.color, bn = Qf !== void 0 ? Qf.length : 0, uf = Lt.get(At), hn = y.state.lights;
      if (A === true && (Q === true || st !== M)) {
        const X3 = st === M && At.id === H;
        ee.setState(At, st, X3);
      }
      let If = false;
      At.version === uf.__version ? (uf.needsLights && uf.lightsStateVersion !== hn.state.version || uf.outputColorSpace !== Ce || Jt.isBatchedMesh && uf.batching === false || !Jt.isBatchedMesh && uf.batching === true || Jt.isBatchedMesh && uf.batchingColor === true && Jt.colorTexture === null || Jt.isBatchedMesh && uf.batchingColor === false && Jt.colorTexture !== null || Jt.isInstancedMesh && uf.instancing === false || !Jt.isInstancedMesh && uf.instancing === true || Jt.isSkinnedMesh && uf.skinning === false || !Jt.isSkinnedMesh && uf.skinning === true || Jt.isInstancedMesh && uf.instancingColor === true && Jt.instanceColor === null || Jt.isInstancedMesh && uf.instancingColor === false && Jt.instanceColor !== null || Jt.isInstancedMesh && uf.instancingMorph === true && Jt.morphTexture === null || Jt.isInstancedMesh && uf.instancingMorph === false && Jt.morphTexture !== null || uf.envMap !== Ke || At.fog === true && uf.fog !== ie || uf.numClippingPlanes !== void 0 && (uf.numClippingPlanes !== ee.numPlanes || uf.numIntersection !== ee.numIntersection) || uf.vertexAlphas !== Yt || uf.vertexTangents !== Pe || uf.morphTargets !== Ze || uf.morphNormals !== pe || uf.morphColors !== Xf || uf.toneMapping !== Vf || uf.morphTargetsCount !== bn) && (If = true) : (If = true, uf.__version = At.version);
      let vr = uf.currentProgram;
      If === true && (vr = _n(At, Vt, Jt));
      let m1 = false, rr = false, _s = false;
      const kn = vr.getUniforms(), Fr = uf.uniforms;
      if (Pt.useProgram(vr.program) && (m1 = true, rr = true, _s = true), At.id !== H && (H = At.id, rr = true), m1 || M !== st) {
        Pt.buffers.depth.getReversed() && st.reversedDepth !== true && (st._reversedDepth = true, st.updateProjectionMatrix()), kn.setValue(g, "projectionMatrix", st.projectionMatrix), kn.setValue(g, "viewMatrix", st.matrixWorldInverse);
        const F3 = kn.map.cameraPosition;
        F3 !== void 0 && F3.setValue(g, et.setFromMatrixPosition(st.matrixWorld)), ut.logarithmicDepthBuffer && kn.setValue(g, "logDepthBufFC", 2 / (Math.log(st.far + 1) / Math.LN2)), (At.isMeshPhongMaterial || At.isMeshToonMaterial || At.isMeshLambertMaterial || At.isMeshBasicMaterial || At.isMeshStandardMaterial || At.isShaderMaterial) && kn.setValue(g, "isOrthographic", st.isOrthographicCamera === true), M !== st && (M = st, rr = true, _s = true);
      }
      if (Jt.isSkinnedMesh) {
        kn.setOptional(g, Jt, "bindMatrix"), kn.setOptional(g, Jt, "bindMatrixInverse");
        const X3 = Jt.skeleton;
        X3 && (X3.boneTexture === null && X3.computeBoneTexture(), kn.setValue(g, "boneTexture", X3.boneTexture, Mt));
      }
      Jt.isBatchedMesh && (kn.setOptional(g, Jt, "batchingTexture"), kn.setValue(g, "batchingTexture", Jt._matricesTexture, Mt), kn.setOptional(g, Jt, "batchingIdTexture"), kn.setValue(g, "batchingIdTexture", Jt._indirectTexture, Mt), kn.setOptional(g, Jt, "batchingColorTexture"), Jt._colorsTexture !== null && kn.setValue(g, "batchingColorTexture", Jt._colorsTexture, Mt));
      const Rr = Ct.morphAttributes;
      if ((Rr.position !== void 0 || Rr.normal !== void 0 || Rr.color !== void 0) && ne.update(Jt, Ct, vr), (rr || uf.receiveShadow !== Jt.receiveShadow) && (uf.receiveShadow = Jt.receiveShadow, kn.setValue(g, "receiveShadow", Jt.receiveShadow)), At.isMeshGouraudMaterial && At.envMap !== null && (Fr.envMap.value = Ke, Fr.flipEnvMap.value = Ke.isCubeTexture && Ke.isRenderTargetTexture === false ? -1 : 1), At.isMeshStandardMaterial && At.envMap === null && Vt.environment !== null && (Fr.envMapIntensity.value = Vt.environmentIntensity), rr && (kn.setValue(g, "toneMappingExposure", x.toneMappingExposure), uf.needsLights && F5(Fr, _s), ie && At.fog === true && Dt.refreshFogUniforms(Fr, ie), Dt.refreshMaterialUniforms(Fr, At, G, V, y.state.transmissionRenderTarget[st.id]), A6.upload(g, L7(uf), Fr, Mt)), At.isShaderMaterial && At.uniformsNeedUpdate === true && (A6.upload(g, L7(uf), Fr, Mt), At.uniformsNeedUpdate = false), At.isSpriteMaterial && kn.setValue(g, "center", Jt.center), kn.setValue(g, "modelViewMatrix", Jt.modelViewMatrix), kn.setValue(g, "normalMatrix", Jt.normalMatrix), kn.setValue(g, "modelMatrix", Jt.matrixWorld), At.isShaderMaterial || At.isRawShaderMaterial) {
        const X3 = At.uniformsGroups;
        for (let F3 = 0, ni = X3.length; F3 < ni; F3++) {
          const Jb = X3[F3];
          jt.update(Jb, vr), jt.bind(Jb, vr);
        }
      }
      return vr;
    }
    function F5(st, Vt) {
      st.ambientLightColor.needsUpdate = Vt, st.lightProbe.needsUpdate = Vt, st.directionalLights.needsUpdate = Vt, st.directionalLightShadows.needsUpdate = Vt, st.pointLights.needsUpdate = Vt, st.pointLightShadows.needsUpdate = Vt, st.spotLights.needsUpdate = Vt, st.spotLightShadows.needsUpdate = Vt, st.rectAreaLights.needsUpdate = Vt, st.hemisphereLights.needsUpdate = Vt;
    }
    function H7(st) {
      return st.isMeshLambertMaterial || st.isMeshToonMaterial || st.isMeshPhongMaterial || st.isMeshStandardMaterial || st.isShadowMaterial || st.isShaderMaterial && st.lights === true;
    }
    this.getActiveCubeFace = function() {
      return O;
    }, this.getActiveMipmapLevel = function() {
      return N;
    }, this.getRenderTarget = function() {
      return i;
    }, this.setRenderTargetTextures = function(st, Vt, Ct) {
      const At = Lt.get(st);
      At.__autoAllocateDepthBuffer = st.resolveDepthBuffer === false, At.__autoAllocateDepthBuffer === false && (At.__useRenderToTexture = false), Lt.get(st.texture).__webglTexture = Vt, Lt.get(st.depthTexture).__webglTexture = At.__autoAllocateDepthBuffer ? void 0 : Ct, At.__hasExternalTextures = true;
    }, this.setRenderTargetFramebuffer = function(st, Vt) {
      const Ct = Lt.get(st);
      Ct.__webglFramebuffer = Vt, Ct.__useDefaultFramebuffer = Vt === void 0;
    };
    const Mz = g.createFramebuffer();
    this.setRenderTarget = function(st, Vt = 0, Ct = 0) {
      i = st, O = Vt, N = Ct;
      let At = true, Jt = null, ie = false, ke = false;
      if (st) {
        const Ke = Lt.get(st);
        if (Ke.__useDefaultFramebuffer !== void 0) Pt.bindFramebuffer(g.FRAMEBUFFER, null), At = false;
        else if (Ke.__webglFramebuffer === void 0) Mt.setupRenderTarget(st);
        else if (Ke.__hasExternalTextures) Mt.rebindTextures(st, Lt.get(st.texture).__webglTexture, Lt.get(st.depthTexture).__webglTexture);
        else if (st.depthBuffer) {
          const Ze = st.depthTexture;
          if (Ke.__boundDepthTexture !== Ze) {
            if (Ze !== null && Lt.has(Ze) && (st.width !== Ze.image.width || st.height !== Ze.image.height)) throw new Error("WebGLRenderTarget: Attached DepthTexture is initialized to the incorrect size.");
            Mt.setupDepthRenderbuffer(st);
          }
        }
        const Yt = st.texture;
        (Yt.isData3DTexture || Yt.isDataArrayTexture || Yt.isCompressedArrayTexture) && (ke = true);
        const Pe = Lt.get(st).__webglFramebuffer;
        st.isWebGLCubeRenderTarget ? (Array.isArray(Pe[Vt]) ? Jt = Pe[Vt][Ct] : Jt = Pe[Vt], ie = true) : st.samples > 0 && Mt.useMultisampledRTT(st) === false ? Jt = Lt.get(st).__webglMultisampledFramebuffer : Array.isArray(Pe) ? Jt = Pe[Ct] : Jt = Pe, p.copy(st.viewport), T.copy(st.scissor), w = st.scissorTest;
      } else p.copy(K).multiplyScalar(G).floor(), T.copy(_).multiplyScalar(G).floor(), w = vt;
      if (Ct !== 0 && (Jt = Mz), Pt.bindFramebuffer(g.FRAMEBUFFER, Jt) && At && Pt.drawBuffers(st, Jt), Pt.viewport(p), Pt.scissor(T), Pt.setScissorTest(w), ie) {
        const Ke = Lt.get(st.texture);
        g.framebufferTexture2D(g.FRAMEBUFFER, g.COLOR_ATTACHMENT0, g.TEXTURE_CUBE_MAP_POSITIVE_X + Vt, Ke.__webglTexture, Ct);
      } else if (ke) {
        const Ke = Vt;
        for (let Yt = 0; Yt < st.textures.length; Yt++) {
          const Pe = Lt.get(st.textures[Yt]);
          g.framebufferTextureLayer(g.FRAMEBUFFER, g.COLOR_ATTACHMENT0 + Yt, Pe.__webglTexture, Ct, Ke);
        }
      } else if (st !== null && Ct !== 0) {
        const Ke = Lt.get(st.texture);
        g.framebufferTexture2D(g.FRAMEBUFFER, g.COLOR_ATTACHMENT0, g.TEXTURE_2D, Ke.__webglTexture, Ct);
      }
      H = -1;
    }, this.readRenderTargetPixels = function(st, Vt, Ct, At, Jt, ie, ke, Ce = 0) {
      if (!(st && st.isWebGLRenderTarget)) {
        console.error("THREE.WebGLRenderer.readRenderTargetPixels: renderTarget is not THREE.WebGLRenderTarget.");
        return;
      }
      let Ke = Lt.get(st).__webglFramebuffer;
      if (st.isWebGLCubeRenderTarget && ke !== void 0 && (Ke = Ke[ke]), Ke) {
        Pt.bindFramebuffer(g.FRAMEBUFFER, Ke);
        try {
          const Yt = st.textures[Ce], Pe = Yt.format, Ze = Yt.type;
          if (!ut.textureFormatReadable(Pe)) {
            console.error("THREE.WebGLRenderer.readRenderTargetPixels: renderTarget is not in RGBA or implementation defined format.");
            return;
          }
          if (!ut.textureTypeReadable(Ze)) {
            console.error("THREE.WebGLRenderer.readRenderTargetPixels: renderTarget is not in UnsignedByteType or implementation defined type.");
            return;
          }
          Vt >= 0 && Vt <= st.width - At && Ct >= 0 && Ct <= st.height - Jt && (st.textures.length > 1 && g.readBuffer(g.COLOR_ATTACHMENT0 + Ce), g.readPixels(Vt, Ct, At, Jt, it.convert(Pe), it.convert(Ze), ie));
        } finally {
          const Yt = i !== null ? Lt.get(i).__webglFramebuffer : null;
          Pt.bindFramebuffer(g.FRAMEBUFFER, Yt);
        }
      }
    }, this.readRenderTargetPixelsAsync = async function(st, Vt, Ct, At, Jt, ie, ke, Ce = 0) {
      if (!(st && st.isWebGLRenderTarget)) throw new Error("THREE.WebGLRenderer.readRenderTargetPixels: renderTarget is not THREE.WebGLRenderTarget.");
      let Ke = Lt.get(st).__webglFramebuffer;
      if (st.isWebGLCubeRenderTarget && ke !== void 0 && (Ke = Ke[ke]), Ke) if (Vt >= 0 && Vt <= st.width - At && Ct >= 0 && Ct <= st.height - Jt) {
        Pt.bindFramebuffer(g.FRAMEBUFFER, Ke);
        const Yt = st.textures[Ce], Pe = Yt.format, Ze = Yt.type;
        if (!ut.textureFormatReadable(Pe)) throw new Error("THREE.WebGLRenderer.readRenderTargetPixelsAsync: renderTarget is not in RGBA or implementation defined format.");
        if (!ut.textureTypeReadable(Ze)) throw new Error("THREE.WebGLRenderer.readRenderTargetPixelsAsync: renderTarget is not in UnsignedByteType or implementation defined type.");
        const pe = g.createBuffer();
        g.bindBuffer(g.PIXEL_PACK_BUFFER, pe), g.bufferData(g.PIXEL_PACK_BUFFER, ie.byteLength, g.STREAM_READ), st.textures.length > 1 && g.readBuffer(g.COLOR_ATTACHMENT0 + Ce), g.readPixels(Vt, Ct, At, Jt, it.convert(Pe), it.convert(Ze), 0);
        const Xf = i !== null ? Lt.get(i).__webglFramebuffer : null;
        Pt.bindFramebuffer(g.FRAMEBUFFER, Xf);
        const Vf = g.fenceSync(g.SYNC_GPU_COMMANDS_COMPLETE, 0);
        return g.flush(), await Eqt(g, Vf, 4), g.bindBuffer(g.PIXEL_PACK_BUFFER, pe), g.getBufferSubData(g.PIXEL_PACK_BUFFER, 0, ie), g.deleteBuffer(pe), g.deleteSync(Vf), ie;
      } else throw new Error("THREE.WebGLRenderer.readRenderTargetPixelsAsync: requested read bounds are out of range.");
    }, this.copyFramebufferToTexture = function(st, Vt = null, Ct = 0) {
      const At = Math.pow(2, -Ct), Jt = Math.floor(st.image.width * At), ie = Math.floor(st.image.height * At), ke = Vt !== null ? Vt.x : 0, Ce = Vt !== null ? Vt.y : 0;
      Mt.setTexture2D(st, 0), g.copyTexSubImage2D(g.TEXTURE_2D, Ct, 0, 0, ke, Ce, Jt, ie), Pt.unbindTexture();
    };
    const Et = g.createFramebuffer(), oe = g.createFramebuffer();
    this.copyTextureToTexture = function(st, Vt, Ct = null, At = null, Jt = 0, ie = null) {
      ie === null && (Jt !== 0 ? (os("WebGLRenderer: copyTextureToTexture function signature has changed to support src and dst mipmap levels."), ie = Jt, Jt = 0) : ie = 0);
      let ke, Ce, Ke, Yt, Pe, Ze, pe, Xf, Vf;
      const Qf = st.isCompressedTexture ? st.mipmaps[ie] : st.image;
      if (Ct !== null) ke = Ct.max.x - Ct.min.x, Ce = Ct.max.y - Ct.min.y, Ke = Ct.isBox3 ? Ct.max.z - Ct.min.z : 1, Yt = Ct.min.x, Pe = Ct.min.y, Ze = Ct.isBox3 ? Ct.min.z : 0;
      else {
        const Rr = Math.pow(2, -Jt);
        ke = Math.floor(Qf.width * Rr), Ce = Math.floor(Qf.height * Rr), st.isDataArrayTexture ? Ke = Qf.depth : st.isData3DTexture ? Ke = Math.floor(Qf.depth * Rr) : Ke = 1, Yt = 0, Pe = 0, Ze = 0;
      }
      At !== null ? (pe = At.x, Xf = At.y, Vf = At.z) : (pe = 0, Xf = 0, Vf = 0);
      const bn = it.convert(Vt.format), uf = it.convert(Vt.type);
      let hn;
      Vt.isData3DTexture ? (Mt.setTexture3D(Vt, 0), hn = g.TEXTURE_3D) : Vt.isDataArrayTexture || Vt.isCompressedArrayTexture ? (Mt.setTexture2DArray(Vt, 0), hn = g.TEXTURE_2D_ARRAY) : (Mt.setTexture2D(Vt, 0), hn = g.TEXTURE_2D), g.pixelStorei(g.UNPACK_FLIP_Y_WEBGL, Vt.flipY), g.pixelStorei(g.UNPACK_PREMULTIPLY_ALPHA_WEBGL, Vt.premultiplyAlpha), g.pixelStorei(g.UNPACK_ALIGNMENT, Vt.unpackAlignment);
      const If = g.getParameter(g.UNPACK_ROW_LENGTH), vr = g.getParameter(g.UNPACK_IMAGE_HEIGHT), m1 = g.getParameter(g.UNPACK_SKIP_PIXELS), rr = g.getParameter(g.UNPACK_SKIP_ROWS), _s = g.getParameter(g.UNPACK_SKIP_IMAGES);
      g.pixelStorei(g.UNPACK_ROW_LENGTH, Qf.width), g.pixelStorei(g.UNPACK_IMAGE_HEIGHT, Qf.height), g.pixelStorei(g.UNPACK_SKIP_PIXELS, Yt), g.pixelStorei(g.UNPACK_SKIP_ROWS, Pe), g.pixelStorei(g.UNPACK_SKIP_IMAGES, Ze);
      const kn = st.isDataArrayTexture || st.isData3DTexture, Fr = Vt.isDataArrayTexture || Vt.isData3DTexture;
      if (st.isDepthTexture) {
        const Rr = Lt.get(st), X3 = Lt.get(Vt), F3 = Lt.get(Rr.__renderTarget), ni = Lt.get(X3.__renderTarget);
        Pt.bindFramebuffer(g.READ_FRAMEBUFFER, F3.__webglFramebuffer), Pt.bindFramebuffer(g.DRAW_FRAMEBUFFER, ni.__webglFramebuffer);
        for (let Jb = 0; Jb < Ke; Jb++) kn && (g.framebufferTextureLayer(g.READ_FRAMEBUFFER, g.COLOR_ATTACHMENT0, Lt.get(st).__webglTexture, Jt, Ze + Jb), g.framebufferTextureLayer(g.DRAW_FRAMEBUFFER, g.COLOR_ATTACHMENT0, Lt.get(Vt).__webglTexture, ie, Vf + Jb)), g.blitFramebuffer(Yt, Pe, ke, Ce, pe, Xf, ke, Ce, g.DEPTH_BUFFER_BIT, g.NEAREST);
        Pt.bindFramebuffer(g.READ_FRAMEBUFFER, null), Pt.bindFramebuffer(g.DRAW_FRAMEBUFFER, null);
      } else if (Jt !== 0 || st.isRenderTargetTexture || Lt.has(st)) {
        const Rr = Lt.get(st), X3 = Lt.get(Vt);
        Pt.bindFramebuffer(g.READ_FRAMEBUFFER, Et), Pt.bindFramebuffer(g.DRAW_FRAMEBUFFER, oe);
        for (let F3 = 0; F3 < Ke; F3++) kn ? g.framebufferTextureLayer(g.READ_FRAMEBUFFER, g.COLOR_ATTACHMENT0, Rr.__webglTexture, Jt, Ze + F3) : g.framebufferTexture2D(g.READ_FRAMEBUFFER, g.COLOR_ATTACHMENT0, g.TEXTURE_2D, Rr.__webglTexture, Jt), Fr ? g.framebufferTextureLayer(g.DRAW_FRAMEBUFFER, g.COLOR_ATTACHMENT0, X3.__webglTexture, ie, Vf + F3) : g.framebufferTexture2D(g.DRAW_FRAMEBUFFER, g.COLOR_ATTACHMENT0, g.TEXTURE_2D, X3.__webglTexture, ie), Jt !== 0 ? g.blitFramebuffer(Yt, Pe, ke, Ce, pe, Xf, ke, Ce, g.COLOR_BUFFER_BIT, g.NEAREST) : Fr ? g.copyTexSubImage3D(hn, ie, pe, Xf, Vf + F3, Yt, Pe, ke, Ce) : g.copyTexSubImage2D(hn, ie, pe, Xf, Yt, Pe, ke, Ce);
        Pt.bindFramebuffer(g.READ_FRAMEBUFFER, null), Pt.bindFramebuffer(g.DRAW_FRAMEBUFFER, null);
      } else Fr ? st.isDataTexture || st.isData3DTexture ? g.texSubImage3D(hn, ie, pe, Xf, Vf, ke, Ce, Ke, bn, uf, Qf.data) : Vt.isCompressedArrayTexture ? g.compressedTexSubImage3D(hn, ie, pe, Xf, Vf, ke, Ce, Ke, bn, Qf.data) : g.texSubImage3D(hn, ie, pe, Xf, Vf, ke, Ce, Ke, bn, uf, Qf) : st.isDataTexture ? g.texSubImage2D(g.TEXTURE_2D, ie, pe, Xf, ke, Ce, bn, uf, Qf.data) : st.isCompressedTexture ? g.compressedTexSubImage2D(g.TEXTURE_2D, ie, pe, Xf, Qf.width, Qf.height, bn, Qf.data) : g.texSubImage2D(g.TEXTURE_2D, ie, pe, Xf, ke, Ce, bn, uf, Qf);
      g.pixelStorei(g.UNPACK_ROW_LENGTH, If), g.pixelStorei(g.UNPACK_IMAGE_HEIGHT, vr), g.pixelStorei(g.UNPACK_SKIP_PIXELS, m1), g.pixelStorei(g.UNPACK_SKIP_ROWS, rr), g.pixelStorei(g.UNPACK_SKIP_IMAGES, _s), ie === 0 && Vt.generateMipmaps && g.generateMipmap(hn), Pt.unbindTexture();
    }, this.initRenderTarget = function(st) {
      Lt.get(st).__webglFramebuffer === void 0 && Mt.setupRenderTarget(st);
    }, this.initTexture = function(st) {
      st.isCubeTexture ? Mt.setTextureCube(st, 0) : st.isData3DTexture ? Mt.setTexture3D(st, 0) : st.isDataArrayTexture || st.isCompressedArrayTexture ? Mt.setTexture2DArray(st, 0) : Mt.setTexture2D(st, 0), Pt.unbindTexture();
    }, this.resetState = function() {
      O = 0, N = 0, i = null, Pt.reset(), Rt.reset();
    }, typeof __THREE_DEVTOOLS__ < "u" && __THREE_DEVTOOLS__.dispatchEvent(new CustomEvent("observe", { detail: this }));
  }
  get coordinateSystem() {
    return mz;
  }
  get outputColorSpace() {
    return this._outputColorSpace;
  }
  set outputColorSpace(e) {
    this._outputColorSpace = e;
    const f = this.getContext();
    f.drawingBufferColorSpace = Uf._getDrawingBufferColorSpace(e), f.unpackColorSpace = Uf._getUnpackColorSpace();
  }
}
const cf = (t) => {
  const e = Math.sin(t * 127.1 + 311.7) * 43758.5453;
  return e - Math.floor(e);
};
function _6(t) {
  const e = document.createElement("canvas");
  e.width = e.height = 256;
  const f = e.getContext("2d");
  if (t === "leaves") for (let v = 0; v < 115; v++) {
    const r = 28 + cf(v) * 200, z = 25 + cf(v + 61) * 205;
    f.save(), f.translate(r, z), f.rotate(cf(v + 9) * 6.28), f.fillStyle = ["#566a32", "#354b23", "#788048", "#445d2d"][v % 4], f.beginPath(), f.ellipse(0, 0, 12 + cf(v + 5) * 9, 6 + cf(v + 99) * 6, 0, 0, Math.PI * 2), f.fill(), f.strokeStyle = "#879058", f.lineWidth = 0.6, f.beginPath(), f.moveTo(-12, 0), f.lineTo(12, 0), f.stroke(), f.restore();
  }
  else {
    f.fillStyle = { grass: "#586342", paving: "#a6a194", asphalt: "#373b3d" }[t], f.fillRect(0, 0, 256, 256);
    for (let v = 0; v < 22e3; v++) {
      const r = cf(v);
      f.fillStyle = r > 0.5 ? "rgba(230,224,185,.09)" : "rgba(8,19,14,.12)", f.fillRect(cf(v + 1) * 256, cf(v + 3) * 256, t === "grass" ? 1 : 2, t === "grass" ? 3 : 1);
    }
    if (t === "paving") {
      f.strokeStyle = "#686b65", f.lineWidth = 1;
      for (let v = 0; v <= 256; v += 64) f.beginPath(), f.moveTo(v, 0), f.lineTo(v, 256), f.moveTo(0, v), f.lineTo(256, v), f.stroke();
    }
  }
  const n = new TM(e);
  return n.colorSpace = Qv, n.wrapS = n.wrapT = e6, n.anisotropy = 8, n;
}
function buildLandscapeTemplate(t) {
  const e = { grass: new x7({ map: _6("grass"), roughness: 1 }), paving: new x7({ map: _6("paving"), roughness: 0.95 }), asphalt: new x7({ map: _6("asphalt"), roughness: 0.96 }) };
  e.grass.map.repeat.set(450, 450), e.paving.map.repeat.set(35, 20), e.asphalt.map.repeat.set(65, 3);
  for (const [A, Q] of Object.entries(e)) Q.onBeforeCompile = (B) => {
    B.vertexShader = `varying vec3 vGroundPosition;
` + B.vertexShader, B.vertexShader = B.vertexShader.replace("#include <worldpos_vertex>", `#include <worldpos_vertex>
vGroundPosition=(modelMatrix*vec4(transformed,1.)).xyz;`), B.fragmentShader = `varying vec3 vGroundPosition;
    float ghash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
    float gnoise(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(ghash(i),ghash(i+vec2(1,0)),f.x),mix(ghash(i+vec2(0,1)),ghash(i+1.),f.x),f.y);}
   ` + B.fragmentShader;
    const et = A === "grass" ? `float meadowVariation=gnoise(vGroundPosition.xz*.08)*.6+gnoise(vGroundPosition.xz*.31)*.3+gnoise(vGroundPosition.xz*1.8)*.1;
    diffuseColor.rgb*=mix(vec3(.64,.72,.54),vec3(1.3,1.19,.92),meadowVariation);
    diffuseColor.rgb*=.93+.07*gnoise(vGroundPosition.xz*3.1);` : A === "asphalt" ? `float grain=gnoise(vGroundPosition.xz*95.);float wear=gnoise(vGroundPosition.xz*.16);diffuseColor.rgb*=mix(.8,1.18,wear)*mix(.86,1.13,grain);
    float track=exp(-pow((abs(vGroundPosition.z-64.)-3.1)*1.8,2.));diffuseColor.rgb*=1.-track*.1;` : "vec2 tile=floor(vGroundPosition.xz*1.25);float joint=min(fract(vGroundPosition.x*1.25),fract(vGroundPosition.z*1.25));diffuseColor.rgb*=mix(.86,1.08,ghash(tile));diffuseColor.rgb*=mix(.68,1.,smoothstep(0.,.017,joint));";
    B.fragmentShader = B.fragmentShader.replace("#include <color_fragment>", `#include <color_fragment>
` + et);
  }, Q.customProgramCacheKey = () => A + "-world-v2";
  const f = (A, Q = 0, B = 0.8) => new x7({ color: A, roughness: B, metalness: Q }), n = f("#a9aaa0"), v = f("#d8d6bd"), r = f("#5b4935"), z = f("#3c4445", 0.65, 0.4), u = f("#355364", 0.5, 0.2);
  function b(A, Q, B, et, nt, I, U) {
    const J = new Zn(new Lz(A, Q, B), U);
    return J.position.set(et, nt, I), J.castShadow = true, J.receiveShadow = true, t.add(J), J;
  }
  function a(A, Q, B, et, nt, I) {
    const U = new Zn(new j9(A, Q), I);
    return U.rotation.x = -Math.PI / 2, U.position.set(B, nt, et), U.receiveShadow = true, t.add(U), U;
  }
  const P = new DM([[-105, 0, 18], [-95, 0, 45], [-65, 0, 56], [0, 0, 56], [66, 0, 55], [94, 0, 36], [101, 0, 0], [89, 0, -42], [46, 0, -56], [-32, 0, -57], [-88, 0, -40]].map((A) => new ht(...A)), true, "catmullrom", 0.3), s = new DM([[83, 0, 48], [117, 0, 71], [144, 0, 115], [203, 0, 156], [300, 0, 160]].map((A) => new ht(...A))), d = P.getSpacedPoints(320), c = s.getSpacedPoints(100), l = (A, Q, B, et, nt, I = false) => {
    const U = [], J = [], g = [];
    for (let Pt = 0; Pt <= 320; Pt++) {
      const xt = Pt / 320, Lt = A.getPointAt(xt), Mt = A.getTangentAt(xt), Ht = -Mt.z, Gt = Mt.x;
      for (const ft of [-1, 1]) {
        const $ = B + ft * Q / 2;
        U.push(Lt.x + Ht * $, et, Lt.z + Gt * $), J.push(xt, ft === 1 ? 1 : 0);
      }
      if (Pt < 320 && !(I && (Lt.x - 83) ** 2 + (Lt.z - 48) ** 2 < 200)) {
        const ft = Pt * 2;
        g.push(ft, ft + 1, ft + 2, ft + 1, ft + 3, ft + 2);
      }
    }
    const zt = new bv();
    zt.setAttribute("position", new ln(U, 3)), zt.setAttribute("uv", new ln(J, 2)), zt.setIndex(g), zt.computeVertexNormals();
    const ut = new Zn(zt, nt);
    return ut.receiveShadow = true, t.add(ut), ut;
  }, W = f("#407f9e", 0, 0.88), X = f("#d7b76a");
  a(1e4, 1e4, 0, 0, -0.5, e.grass);
  const y = new Is();
  d.forEach((A, Q) => Q ? y.lineTo(A.x, -A.z) : y.moveTo(A.x, -A.z)), y.closePath();
  const m = new Zn(new U6(y), e.paving);
  m.rotation.x = -Math.PI / 2, m.position.y = -0.25, m.receiveShadow = true, t.add(m), l(P, 10, 0, -0.16, e.asphalt), l(s, 10, 0, -0.145, e.asphalt);
  for (const A of [-1, 1]) l(P, 0.24, A * 5.25, -0.04, n, true), l(P, 0.12, A * 4.45, -0.135, v, true), l(s, 0.22, A * 5.2, -0.035, n), l(s, 0.11, A * 4.45, -0.125, v);
  l(P, 1.65, -6.2, -0.075, W), l(P, 0.1, -7.08, -0.05, v), l(P, 0.09, 0.08, -0.125, X, true), l(s, 1.65, 6.2, -0.065, W), l(s, 0.1, 0, -0.125, X);
  const j = [[-55, 45, 43, 3], [52, 44, 38, 3], [-48, -46, 36, 3], [35, -47, 36, 3], [-89, 0, 3, 38]];
  j.forEach(([A, Q, B, et]) => {
    b(B + 0.25, 0.19, et + 0.25, A, -0.035, Q, n), a(B, et, A, Q, 0.07, e.grass);
  }), a(20, 14, 0, 39, -0.11, e.paving), a(25, 12, -12, -40, -0.11, e.paving);
  const roadPoints = [...d.filter((B, et) => et % 4 === 0), ...c.filter((B, et) => et % 3 === 0)];
  const x = (A, Q) => roadDistance(roadPoints, A, Q), L = (A, Q) => {
    const B = Math.max(Math.abs(A) - 112, Math.abs(Q) - 73, 0), et = a6.smoothstep(B, 0, 52), nt = a6.smoothstep(x(A, Q), 9, 30);
    return et * nt * (33 * Math.exp(-((A + 155) ** 2 / 18e3 + (Q - 120) ** 2 / 13e3)) + 43 * Math.exp(-((A - 120) ** 2 / 2e4 + (Q + 160) ** 2 / 17e3)) + 8 * Math.exp(-((A + 250) ** 2 / 22e3 + (Q + 270) ** 2 / 32e3)));
  }, O = new j9(1300, 1100, 115, 100);
  O.rotateX(-Math.PI / 2);
  const N = O.attributes.position;
  for (let A = 0; A < N.count; A++) {
    const Q = N.getX(A), B = N.getZ(A);
    N.setY(A, L(Q, B) - 0.46);
  }
  O.computeVertexNormals();
  const i = new Zn(O, e.grass);
  i.receiveShadow = true, t.add(i);
  const H = [];
  for (let A = 0; A < 2350; A++) {
    const Q = -380 + cf(A + 800) * 760, B = -360 + cf(A + 420) * 630;
    Math.abs(Q) < 113 && Math.abs(B) < 75 || x(Q, B) < 12 || B > 185 && Q > 30 || H.push({ x: Q, y: L(Q, B) - 0.15, z: B, s: 1.03 + cf(A + 11) * 1.05 });
  }
  const M = new ks(new Ss(0.11, 0.24, 4, 7), r, H.length * 4), p = new x7({ map: _6("leaves"), alphaTest: 0.25, alphaToCoverage: true, side: $3, roughness: 0.9, color: "#ffffff" }), T = 165;
  p.flatShading = false;
  const w = new ks(new j9(2.65, 2.3), p, H.length * T), Y = new zv(), q = new Nf();
  H.forEach((A, Q) => {
    for (let B = 0; B < 4; B++) Y.position.set(A.x + (B ? Math.cos(B * 2) * 0.65 * A.s : 0), A.y + (B ? 3.5 : 2) * A.s, A.z + (B ? Math.sin(B * 2) * 0.65 * A.s : 0)), Y.rotation.set(B ? 0.5 * Math.sin(B * 2) : 0, 0, B ? 0.5 * Math.cos(B * 2) : 0), Y.scale.set(A.s * (B ? 0.5 : 1), A.s * (B ? 0.72 : 1), A.s * (B ? 0.5 : 1)), Y.updateMatrix(), M.setMatrixAt(Q * 4 + B, Y.matrix);
    for (let B = 0; B < T; B++) {
      const et = Q * T + B, nt = cf(et) * 6.283, I = cf(et + 700) * 2 - 1, U = Math.cbrt(cf(et + 1400)), J = (3.1 + cf(Q + 400) * 1.5) * U * Math.sqrt(1 - I * I);
      Y.position.set(A.x + (Math.cos(nt) * J + Math.cos(B % 3 * 2.1) * 0.55) * A.s, A.y + (5.5 + I * (2.1 + cf(Q + 59) * 2.1) * U) * A.s, A.z + (Math.sin(nt) * J + Math.sin(B % 3 * 2.1) * 0.55) * A.s), Y.rotation.set(cf(et + 9) * 3.14, nt, cf(et + 15) * 3.14), Y.scale.setScalar(A.s * (0.72 + cf(et + 91) * 0.6)), Y.updateMatrix(), w.setMatrixAt(et, Y.matrix), q.setHSL(0.205 + cf(Q + 180) * 0.045, 0.22 + cf(Q) * 0.16, 0.48 + cf(et + 1) * 0.16), w.setColorAt(et, q);
    }
  }), M.castShadow = true, w.castShadow = true, w.receiveShadow = true, t.add(M, w);
  const D = new ks(new j9(0.8, 0.8), p, 4200);
  for (let A = 0; A < 4200; A++) {
    const [Q, B, et, nt] = j[A % j.length];
    Y.position.set(Q + (cf(A + 211) - 0.5) * (et - 1), 0.3 + cf(A + 801) * 0.9, B + (cf(A + 71) - 0.5) * (nt - 1)), Y.rotation.set(cf(A + 5) * 2.2, cf(A + 99) * 6.28, cf(A + 87)), Y.scale.setScalar(0.8 + cf(A + 32) * 0.7), Y.updateMatrix(), D.setMatrixAt(A, Y.matrix), q.setHSL(0.21 + cf(A) * 0.035, 0.32, 0.54 + cf(A + 66) * 0.15), D.setColorAt(A, q);
  }
  D.castShadow = true, D.receiveShadow = true, t.add(D);
  const V = new ks(new I6(1, 9, 6), f("#465334"), 280);
  for (let A = 0; A < 280; A++) {
    const Q = j[A % j.length], [B, et, nt, I] = Q;
    Y.position.set(B + (cf(A + 250) - 0.5) * (nt - 2), 0.4, et + (cf(A + 730) - 0.5) * (I - 1)), Y.rotation.set(0, cf(A) * 6, 0), Y.scale.set(0.5 + cf(A) * 0.6, 0.22 + cf(A + 100) * 0.3, 0.5 + cf(A + 8) * 0.5), Y.updateMatrix(), V.setMatrixAt(A, Y.matrix);
  }
  V.castShadow = true, V.receiveShadow = true, t.add(V);
  for (let A = 0; A < 25; A++) {
    const Q = A / 25, B = P.getPointAt(Q), et = P.getTangentAt(Q), nt = B.x + et.z * 7.7, I = B.z - et.x * 7.7;
    b(0.12, 5.6, 0.12, nt, 2.7, I, z), b(0.75, 0.1, 0.22, nt + 0.3, 5.46, I, z);
  }
  const G = f("#ece5c8");
  for (let A = 0; A < 32; A++) {
    const Q = A / 32, B = P.getPointAt(Q), et = P.getTangentAt(Q), nt = B.x + et.z * 9, I = B.z - et.x * 9;
    b(0.16, 1.3, 0.16, nt, 0.6, I, z), b(0.06, 0.82, 0.175, nt, 0.65, I, G);
  }
  const R = new ks(new j9(0.22, 0.22), new x7({ color: "#8c78a1", side: $3, roughness: 1 }), 1800);
  for (let A = 0; A < 1800; A++) {
    const Q = 113 + cf(A + 401) * 20, B = -35 + cf(A + 511) * 64;
    x(Q, B) < 10 ? Y.scale.setScalar(0) : Y.scale.setScalar(0.6 + cf(A) * 1.1), Y.position.set(Q, L(Q, B) + 0.35 + cf(A + 88) * 0.3, B), Y.rotation.set(-0.8, cf(A + 22) * 6.28, 0), Y.updateMatrix(), R.setMatrixAt(A, Y.matrix);
  }
  t.add(R);
  const k = f("#92968f"), K = f("#aaa99f"), _ = f("#737a7b");
  b(1050, 1.1, 11, 0, 12, -255, k), b(1050, 0.2, 9.5, 0, 12.68, -255, e.asphalt);
  for (const A of [-260.4, -249.6]) b(1050, 1, 0.22, 0, 13, A, k);
  for (let A = -490; A < 500; A += 31) b(1.8, 12, 2.6, A, 6, -255, k);
  for (let A = 0; A < 44; A++) {
    const Q = -660 + A * 30 + cf(A) * 12, B = -475 - cf(A + 12) * 180, et = 12 + cf(A + 8) * 13, nt = 12 + cf(A + 42) * 18, I = 12 + cf(A + 76) * 27;
    b(et, I, nt, Q, I / 2, B, K), b(et + 0.3, 0.4, nt + 0.3, Q, I, B, _);
    for (let U = 3; U < I; U += 3.2) b(et + 0.05, 0.6, nt + 0.05, Q, U, B, _);
  }
  const vt = [];
  for (let A = 0; A < 6; A++) {
    const Q = new D3(), B = f(["#b9bab3", "#253d50", "#883c31", "#bdb7a7", "#4e5353", "#c9c9c3"][A], 0.45, 0.3), et = (I, U, J, g, tt) => {
      const zt = new Zn(I, U);
      return zt.position.set(J, g, tt), zt.castShadow = false, Q.add(zt), zt;
    }, nt = new Is();
    nt.moveTo(-2.15, 0.38), nt.lineTo(-2.1, 0.78), nt.lineTo(-1.15, 1), nt.lineTo(-0.6, 1.5), nt.lineTo(0.75, 1.5), nt.lineTo(1.4, 0.93), nt.lineTo(2.1, 0.78), nt.lineTo(2.15, 0.38), nt.closePath(), et(new kM(nt, { depth: 1.7, bevelEnabled: true, bevelSegments: 2, steps: 1, bevelSize: 0.08, bevelThickness: 0.05 }), B, 0, 0, -0.85);
    for (const I of [-0.91, 0.91]) {
      const U = new Is();
      U.moveTo(-0.97, 1), U.lineTo(-0.51, 1.41), U.lineTo(0.66, 1.41), U.lineTo(1.15, 1), U.closePath();
      const J = et(new U6(U), u, 0, 0, I);
      J.material = new x7({ color: "#283e48", metalness: 0.45, roughness: 0.18, side: $3 });
    }
    for (const I of [-1.35, 1.35]) for (const U of [-0.92, 0.92]) {
      const J = et(new Ss(0.34, 0.34, 0.18, 16), z, I, 0.34, U);
      J.rotation.x = Math.PI / 2;
      const g = et(new Ss(0.19, 0.19, 0.185, 12), n, I, 0.34, U);
      g.rotation.x = Math.PI / 2;
    }
    for (const I of [-0.58, 0.58]) et(new Lz(0.04, 0.18, 0.4), v, 2.2, 0.67, I), et(new Lz(0.04, 0.17, 0.38), f("#913023"), -2.2, 0.65, I);
    t.add(Q), vt.push(Q);
  }
  const rt = [];
  for (let A = 0; A < 28; A++) {
    const Q = new D3(), B = f(["#b58c6c", "#d1ac88", "#967456"][A % 3]), et = f(["#354e62", "#c8c3b4", "#9b5d49", "#555c4b", "#6b7786"][A % 5]), nt = f("#30393c"), I = [], U = [], J = new Zn(new I6(0.145, 9, 7), B);
    J.position.y = 1.63, Q.add(J);
    const g = new Zn(new gs(0.17, 0.39, 4, 8), et);
    g.position.y = 1.16, Q.add(g);
    for (const tt of [-1, 1]) {
      const zt = new Zn(new gs(0.06, 0.43, 3, 6), et);
      zt.position.set(tt * 0.23, 1.13, 0), Q.add(zt), I.push(zt);
      const ut = new Zn(new gs(0.07, 0.58, 3, 6), nt);
      ut.position.set(tt * 0.095, 0.43, 0), Q.add(ut), U.push(ut);
    }
    Q.traverse((tt) => {
      tt instanceof Zn && (tt.castShadow = false);
    }), Q.scale.setScalar(0.94 + cf(A) * 0.16), t.add(Q), rt.push({ group: Q, arms: I, legs: U });
  }
  return { road: P, cars: vt, people: rt };
}

function jSt(t) {
  const template = preparedLandscape("landscape:" + import.meta.url, () => {
    const root = new D3();
    return { root, ...buildLandscapeTemplate(root) };
  });
  // 复用只读几何与固定实例数据；运行对象、材质和GPU资源各场景独立，旧场景释放不能损坏新场景。
  const root = template.root.clone(true), originals = [], copies = [], geometries = new Map(), materials = new Map(), textures = new Map();
  template.root.traverse(object => originals.push(object));
  root.traverse(object => copies.push(object));
  const objects = new Map(originals.map((object, index) => [object, copies[index]]));
  function geometry(source) {
    if (!geometries.has(source)) {
      const copy = new bv();
      copy.index = source.index; copy.attributes = { ...source.attributes };
      copy.groups = source.groups.map(group => ({ ...group })); copy.drawRange = { ...source.drawRange };
      copy.boundingBox = source.boundingBox?.clone() || null; copy.boundingSphere = source.boundingSphere?.clone() || null;
      geometries.set(source, copy);
    }
    return geometries.get(source);
  }
  function material(source) {
    if (!materials.has(source)) {
      const copy = source.clone();
      copy.onBeforeCompile = source.onBeforeCompile; copy.customProgramCacheKey = source.customProgramCacheKey;
      for (const [name, value] of Object.entries(copy)) if (value instanceof Tv) {
        if (!textures.has(value)) textures.set(value, value.clone());
        copy[name] = textures.get(value);
      }
      materials.set(source, copy);
    }
    return materials.get(source);
  }
  root.traverse(object => {
    if (object.geometry) object.geometry = geometry(object.geometry);
    if (object.material) object.material = Array.isArray(object.material) ? object.material.map(material) : material(object.material);
  });
  t.add(root);
  const P = template.road, vt = template.cars.map(object => objects.get(object));
  const rt = template.people.map(person => ({ group: objects.get(person.group), arms: person.arms.map(object => objects.get(object)), legs: person.legs.map(object => objects.get(object)) }));
  return (A) => {
    vt.forEach((Q, B) => {
      const et = B % 2 ? 1 : -1, nt = ((B / 6 + A * 6e-3 * et) % 1 + 1) % 1, I = P.getPointAt(nt), U = P.getTangentAt(nt), J = et * 2.5;
      Q.position.set(I.x - U.z * J, -0.1, I.z + U.x * J), Q.rotation.y = Math.atan2(-U.z, U.x) + (et < 0 ? Math.PI : 0);
    }), rt.forEach((Q, B) => {
      const et = B % 2 ? 1 : -1, nt = ((B / 28 + A * 15e-4 * et) % 1 + 1) % 1, I = P.getPointAt(nt), U = P.getTangentAt(nt);
      Q.group.position.set(I.x + U.z * 6.2, -0.035, I.z - U.x * 6.2), Q.group.rotation.y = Math.atan2(U.x, U.z) + (et < 0 ? Math.PI : 0);
      const J = Math.sin(A * 4 + B) * 0.37;
      Q.arms.forEach((g, tt) => g.rotation.x = tt ? J : -J), Q.legs.forEach((g, tt) => g.rotation.x = tt ? -J : J);
    });
  };
}

const YE = { type: "change" };
const _M = { type: "start" };
const DE = { type: "end" };
const $6 = new W6();
const qE = new q5();
const xSt = Math.cos(70 * a6.DEG2RAD);
const yv = new ht();
const q3 = 2 * Math.PI;
const Xn = { NONE: -1, ROTATE: 0, DOLLY: 1, PAN: 2, TOUCH_ROTATE: 3, TOUCH_PAN: 4, TOUCH_DOLLY_PAN: 5, TOUCH_DOLLY_ROTATE: 6 };
const $M = 1e-6;
class LSt extends WRt {
  constructor(e, f = null) {
    super(e, f), this.state = Xn.NONE, this.target = new ht(), this.cursor = new ht(), this.minDistance = 0, this.maxDistance = 1 / 0, this.minZoom = 0, this.maxZoom = 1 / 0, this.minTargetRadius = 0, this.maxTargetRadius = 1 / 0, this.minPolarAngle = 0, this.maxPolarAngle = Math.PI, this.minAzimuthAngle = -1 / 0, this.maxAzimuthAngle = 1 / 0, this.enableDamping = false, this.dampingFactor = 0.05, this.enableZoom = true, this.zoomSpeed = 1, this.enableRotate = true, this.rotateSpeed = 1, this.keyRotateSpeed = 1, this.enablePan = true, this.panSpeed = 1, this.screenSpacePanning = true, this.keyPanSpeed = 7, this.zoomToCursor = false, this.autoRotate = false, this.autoRotateSpeed = 2, this.keys = { LEFT: "ArrowLeft", UP: "ArrowUp", RIGHT: "ArrowRight", BOTTOM: "ArrowDown" }, this.mouseButtons = { LEFT: Fa.ROTATE, MIDDLE: Fa.DOLLY, RIGHT: Fa.PAN }, this.touches = { ONE: Ra.ROTATE, TWO: Ra.DOLLY_PAN }, this.target0 = this.target.clone(), this.position0 = this.object.position.clone(), this.zoom0 = this.object.zoom, this._domElementKeyEvents = null, this._lastPosition = new ht(), this._lastQuaternion = new Mb(), this._lastTargetPosition = new ht(), this._quat = new Mb().setFromUnitVectors(e.up, new ht(0, 1, 0)), this._quatInverse = this._quat.clone().invert(), this._spherical = new rE(), this._sphericalDelta = new rE(), this._scale = 1, this._panOffset = new ht(), this._rotateStart = new Me(), this._rotateEnd = new Me(), this._rotateDelta = new Me(), this._panStart = new Me(), this._panEnd = new Me(), this._panDelta = new Me(), this._dollyStart = new Me(), this._dollyEnd = new Me(), this._dollyDelta = new Me(), this._dollyDirection = new ht(), this._mouse = new Me(), this._performCursorZoom = false, this._pointers = [], this._pointerPositions = {}, this._controlActive = false, this._onPointerMove = HSt.bind(this), this._onPointerDown = OSt.bind(this), this._onPointerUp = NSt.bind(this), this._onContextMenu = hSt.bind(this), this._onMouseWheel = pSt.bind(this), this._onKeyDown = TSt.bind(this), this._onTouchStart = oSt.bind(this), this._onTouchMove = wSt.bind(this), this._onMouseDown = MSt.bind(this), this._onMouseMove = iSt.bind(this), this._interceptControlDown = GSt.bind(this), this._interceptControlUp = VSt.bind(this), this.domElement !== null && this.connect(this.domElement), this.update();
  }
  connect(e) {
    super.connect(e), this.domElement.addEventListener("pointerdown", this._onPointerDown), this.domElement.addEventListener("pointercancel", this._onPointerUp), this.domElement.addEventListener("contextmenu", this._onContextMenu), this.domElement.addEventListener("wheel", this._onMouseWheel, { passive: false }), this.domElement.getRootNode().addEventListener("keydown", this._interceptControlDown, { passive: true, capture: true }), this.domElement.style.touchAction = "none";
  }
  disconnect() {
    this.domElement.removeEventListener("pointerdown", this._onPointerDown), this.domElement.removeEventListener("pointermove", this._onPointerMove), this.domElement.removeEventListener("pointerup", this._onPointerUp), this.domElement.removeEventListener("pointercancel", this._onPointerUp), this.domElement.removeEventListener("wheel", this._onMouseWheel), this.domElement.removeEventListener("contextmenu", this._onContextMenu), this.stopListenToKeyEvents(), this.domElement.getRootNode().removeEventListener("keydown", this._interceptControlDown, { capture: true }), this.domElement.style.touchAction = "auto";
  }
  dispose() {
    this.disconnect();
  }
  getPolarAngle() {
    return this._spherical.phi;
  }
  getAzimuthalAngle() {
    return this._spherical.theta;
  }
  getDistance() {
    return this.object.position.distanceTo(this.target);
  }
  listenToKeyEvents(e) {
    e.addEventListener("keydown", this._onKeyDown), this._domElementKeyEvents = e;
  }
  stopListenToKeyEvents() {
    this._domElementKeyEvents !== null && (this._domElementKeyEvents.removeEventListener("keydown", this._onKeyDown), this._domElementKeyEvents = null);
  }
  saveState() {
    this.target0.copy(this.target), this.position0.copy(this.object.position), this.zoom0 = this.object.zoom;
  }
  reset() {
    this.target.copy(this.target0), this.object.position.copy(this.position0), this.object.zoom = this.zoom0, this.object.updateProjectionMatrix(), this.dispatchEvent(YE), this.update(), this.state = Xn.NONE;
  }
  update(e = null) {
    const f = this.object.position;
    yv.copy(f).sub(this.target), yv.applyQuaternion(this._quat), this._spherical.setFromVector3(yv), this.autoRotate && this.state === Xn.NONE && this._rotateLeft(this._getAutoRotationAngle(e)), this.enableDamping ? (this._spherical.theta += this._sphericalDelta.theta * this.dampingFactor, this._spherical.phi += this._sphericalDelta.phi * this.dampingFactor) : (this._spherical.theta += this._sphericalDelta.theta, this._spherical.phi += this._sphericalDelta.phi);
    let n = this.minAzimuthAngle, v = this.maxAzimuthAngle;
    isFinite(n) && isFinite(v) && (n < -Math.PI ? n += q3 : n > Math.PI && (n -= q3), v < -Math.PI ? v += q3 : v > Math.PI && (v -= q3), n <= v ? this._spherical.theta = Math.max(n, Math.min(v, this._spherical.theta)) : this._spherical.theta = this._spherical.theta > (n + v) / 2 ? Math.max(n, this._spherical.theta) : Math.min(v, this._spherical.theta)), this._spherical.phi = Math.max(this.minPolarAngle, Math.min(this.maxPolarAngle, this._spherical.phi)), this._spherical.makeSafe(), this.enableDamping === true ? this.target.addScaledVector(this._panOffset, this.dampingFactor) : this.target.add(this._panOffset), this.target.sub(this.cursor), this.target.clampLength(this.minTargetRadius, this.maxTargetRadius), this.target.add(this.cursor);
    let r = false;
    if (this.zoomToCursor && this._performCursorZoom || this.object.isOrthographicCamera) this._spherical.radius = this._clampDistance(this._spherical.radius);
    else {
      const z = this._spherical.radius;
      this._spherical.radius = this._clampDistance(this._spherical.radius * this._scale), r = z != this._spherical.radius;
    }
    if (yv.setFromSpherical(this._spherical), yv.applyQuaternion(this._quatInverse), f.copy(this.target).add(yv), this.object.lookAt(this.target), this.enableDamping === true ? (this._sphericalDelta.theta *= 1 - this.dampingFactor, this._sphericalDelta.phi *= 1 - this.dampingFactor, this._panOffset.multiplyScalar(1 - this.dampingFactor)) : (this._sphericalDelta.set(0, 0, 0), this._panOffset.set(0, 0, 0)), this.zoomToCursor && this._performCursorZoom) {
      let z = null;
      if (this.object.isPerspectiveCamera) {
        const u = yv.length();
        z = this._clampDistance(u * this._scale);
        const b = u - z;
        this.object.position.addScaledVector(this._dollyDirection, b), this.object.updateMatrixWorld(), r = !!b;
      } else if (this.object.isOrthographicCamera) {
        const u = new ht(this._mouse.x, this._mouse.y, 0);
        u.unproject(this.object);
        const b = this.object.zoom;
        this.object.zoom = Math.max(this.minZoom, Math.min(this.maxZoom, this.object.zoom / this._scale)), this.object.updateProjectionMatrix(), r = b !== this.object.zoom;
        const a = new ht(this._mouse.x, this._mouse.y, 0);
        a.unproject(this.object), this.object.position.sub(a).add(u), this.object.updateMatrixWorld(), z = yv.length();
      } else console.warn("WARNING: OrbitControls.js encountered an unknown camera type - zoom to cursor disabled."), this.zoomToCursor = false;
      z !== null && (this.screenSpacePanning ? this.target.set(0, 0, -1).transformDirection(this.object.matrix).multiplyScalar(z).add(this.object.position) : ($6.origin.copy(this.object.position), $6.direction.set(0, 0, -1).transformDirection(this.object.matrix), Math.abs(this.object.up.dot($6.direction)) < xSt ? this.object.lookAt(this.target) : (qE.setFromNormalAndCoplanarPoint(this.object.up, this.target), $6.intersectPlane(qE, this.target))));
    } else if (this.object.isOrthographicCamera) {
      const z = this.object.zoom;
      this.object.zoom = Math.max(this.minZoom, Math.min(this.maxZoom, this.object.zoom / this._scale)), z !== this.object.zoom && (this.object.updateProjectionMatrix(), r = true);
    }
    return this._scale = 1, this._performCursorZoom = false, r || this._lastPosition.distanceToSquared(this.object.position) > $M || 8 * (1 - this._lastQuaternion.dot(this.object.quaternion)) > $M || this._lastTargetPosition.distanceToSquared(this.target) > $M ? (this.dispatchEvent(YE), this._lastPosition.copy(this.object.position), this._lastQuaternion.copy(this.object.quaternion), this._lastTargetPosition.copy(this.target), true) : false;
  }
  _getAutoRotationAngle(e) {
    return e !== null ? q3 / 60 * this.autoRotateSpeed * e : q3 / 60 / 60 * this.autoRotateSpeed;
  }
  _getZoomScale(e) {
    const f = Math.abs(e * 0.01);
    return Math.pow(0.95, this.zoomSpeed * f);
  }
  _rotateLeft(e) {
    this._sphericalDelta.theta -= e;
  }
  _rotateUp(e) {
    this._sphericalDelta.phi -= e;
  }
  _panLeft(e, f) {
    yv.setFromMatrixColumn(f, 0), yv.multiplyScalar(-e), this._panOffset.add(yv);
  }
  _panUp(e, f) {
    this.screenSpacePanning === true ? yv.setFromMatrixColumn(f, 1) : (yv.setFromMatrixColumn(f, 0), yv.crossVectors(this.object.up, yv)), yv.multiplyScalar(e), this._panOffset.add(yv);
  }
  _pan(e, f) {
    const n = this.domElement;
    if (this.object.isPerspectiveCamera) {
      const v = this.object.position;
      yv.copy(v).sub(this.target);
      let r = yv.length();
      r *= Math.tan(this.object.fov / 2 * Math.PI / 180), this._panLeft(2 * e * r / n.clientHeight, this.object.matrix), this._panUp(2 * f * r / n.clientHeight, this.object.matrix);
    } else this.object.isOrthographicCamera ? (this._panLeft(e * (this.object.right - this.object.left) / this.object.zoom / n.clientWidth, this.object.matrix), this._panUp(f * (this.object.top - this.object.bottom) / this.object.zoom / n.clientHeight, this.object.matrix)) : (console.warn("WARNING: OrbitControls.js encountered an unknown camera type - pan disabled."), this.enablePan = false);
  }
  _dollyOut(e) {
    this.object.isPerspectiveCamera || this.object.isOrthographicCamera ? this._scale /= e : (console.warn("WARNING: OrbitControls.js encountered an unknown camera type - dolly/zoom disabled."), this.enableZoom = false);
  }
  _dollyIn(e) {
    this.object.isPerspectiveCamera || this.object.isOrthographicCamera ? this._scale *= e : (console.warn("WARNING: OrbitControls.js encountered an unknown camera type - dolly/zoom disabled."), this.enableZoom = false);
  }
  _updateZoomParameters(e, f) {
    if (!this.zoomToCursor) return;
    this._performCursorZoom = true;
    const n = this.domElement.getBoundingClientRect(), v = e - n.left, r = f - n.top, z = n.width, u = n.height;
    this._mouse.x = v / z * 2 - 1, this._mouse.y = -(r / u) * 2 + 1, this._dollyDirection.set(this._mouse.x, this._mouse.y, 1).unproject(this.object).sub(this.object.position).normalize();
  }
  _clampDistance(e) {
    return Math.max(this.minDistance, Math.min(this.maxDistance, e));
  }
  _handleMouseDownRotate(e) {
    this._rotateStart.set(e.clientX, e.clientY);
  }
  _handleMouseDownDolly(e) {
    this._updateZoomParameters(e.clientX, e.clientX), this._dollyStart.set(e.clientX, e.clientY);
  }
  _handleMouseDownPan(e) {
    this._panStart.set(e.clientX, e.clientY);
  }
  _handleMouseMoveRotate(e) {
    this._rotateEnd.set(e.clientX, e.clientY), this._rotateDelta.subVectors(this._rotateEnd, this._rotateStart).multiplyScalar(this.rotateSpeed);
    const f = this.domElement;
    this._rotateLeft(q3 * this._rotateDelta.x / f.clientHeight), this._rotateUp(q3 * this._rotateDelta.y / f.clientHeight), this._rotateStart.copy(this._rotateEnd), this.update();
  }
  _handleMouseMoveDolly(e) {
    this._dollyEnd.set(e.clientX, e.clientY), this._dollyDelta.subVectors(this._dollyEnd, this._dollyStart), this._dollyDelta.y > 0 ? this._dollyOut(this._getZoomScale(this._dollyDelta.y)) : this._dollyDelta.y < 0 && this._dollyIn(this._getZoomScale(this._dollyDelta.y)), this._dollyStart.copy(this._dollyEnd), this.update();
  }
  _handleMouseMovePan(e) {
    this._panEnd.set(e.clientX, e.clientY), this._panDelta.subVectors(this._panEnd, this._panStart).multiplyScalar(this.panSpeed), this._pan(this._panDelta.x, this._panDelta.y), this._panStart.copy(this._panEnd), this.update();
  }
  _handleMouseWheel(e) {
    this._updateZoomParameters(e.clientX, e.clientY), e.deltaY < 0 ? this._dollyIn(this._getZoomScale(e.deltaY)) : e.deltaY > 0 && this._dollyOut(this._getZoomScale(e.deltaY)), this.update();
  }
  _handleKeyDown(e) {
    let f = false;
    switch (e.code) {
      case this.keys.UP:
        e.ctrlKey || e.metaKey || e.shiftKey ? this.enableRotate && this._rotateUp(q3 * this.keyRotateSpeed / this.domElement.clientHeight) : this.enablePan && this._pan(0, this.keyPanSpeed), f = true;
        break;
      case this.keys.BOTTOM:
        e.ctrlKey || e.metaKey || e.shiftKey ? this.enableRotate && this._rotateUp(-q3 * this.keyRotateSpeed / this.domElement.clientHeight) : this.enablePan && this._pan(0, -this.keyPanSpeed), f = true;
        break;
      case this.keys.LEFT:
        e.ctrlKey || e.metaKey || e.shiftKey ? this.enableRotate && this._rotateLeft(q3 * this.keyRotateSpeed / this.domElement.clientHeight) : this.enablePan && this._pan(this.keyPanSpeed, 0), f = true;
        break;
      case this.keys.RIGHT:
        e.ctrlKey || e.metaKey || e.shiftKey ? this.enableRotate && this._rotateLeft(-q3 * this.keyRotateSpeed / this.domElement.clientHeight) : this.enablePan && this._pan(-this.keyPanSpeed, 0), f = true;
        break;
    }
    f && (e.preventDefault(), this.update());
  }
  _handleTouchStartRotate(e) {
    if (this._pointers.length === 1) this._rotateStart.set(e.pageX, e.pageY);
    else {
      const f = this._getSecondPointerPosition(e), n = 0.5 * (e.pageX + f.x), v = 0.5 * (e.pageY + f.y);
      this._rotateStart.set(n, v);
    }
  }
  _handleTouchStartPan(e) {
    if (this._pointers.length === 1) this._panStart.set(e.pageX, e.pageY);
    else {
      const f = this._getSecondPointerPosition(e), n = 0.5 * (e.pageX + f.x), v = 0.5 * (e.pageY + f.y);
      this._panStart.set(n, v);
    }
  }
  _handleTouchStartDolly(e) {
    const f = this._getSecondPointerPosition(e), n = e.pageX - f.x, v = e.pageY - f.y, r = Math.sqrt(n * n + v * v);
    this._dollyStart.set(0, r);
  }
  _handleTouchStartDollyPan(e) {
    this.enableZoom && this._handleTouchStartDolly(e), this.enablePan && this._handleTouchStartPan(e);
  }
  _handleTouchStartDollyRotate(e) {
    this.enableZoom && this._handleTouchStartDolly(e), this.enableRotate && this._handleTouchStartRotate(e);
  }
  _handleTouchMoveRotate(e) {
    if (this._pointers.length == 1) this._rotateEnd.set(e.pageX, e.pageY);
    else {
      const n = this._getSecondPointerPosition(e), v = 0.5 * (e.pageX + n.x), r = 0.5 * (e.pageY + n.y);
      this._rotateEnd.set(v, r);
    }
    this._rotateDelta.subVectors(this._rotateEnd, this._rotateStart).multiplyScalar(this.rotateSpeed);
    const f = this.domElement;
    this._rotateLeft(q3 * this._rotateDelta.x / f.clientHeight), this._rotateUp(q3 * this._rotateDelta.y / f.clientHeight), this._rotateStart.copy(this._rotateEnd);
  }
  _handleTouchMovePan(e) {
    if (this._pointers.length === 1) this._panEnd.set(e.pageX, e.pageY);
    else {
      const f = this._getSecondPointerPosition(e), n = 0.5 * (e.pageX + f.x), v = 0.5 * (e.pageY + f.y);
      this._panEnd.set(n, v);
    }
    this._panDelta.subVectors(this._panEnd, this._panStart).multiplyScalar(this.panSpeed), this._pan(this._panDelta.x, this._panDelta.y), this._panStart.copy(this._panEnd);
  }
  _handleTouchMoveDolly(e) {
    const f = this._getSecondPointerPosition(e), n = e.pageX - f.x, v = e.pageY - f.y, r = Math.sqrt(n * n + v * v);
    this._dollyEnd.set(0, r), this._dollyDelta.set(0, Math.pow(this._dollyEnd.y / this._dollyStart.y, this.zoomSpeed)), this._dollyOut(this._dollyDelta.y), this._dollyStart.copy(this._dollyEnd);
    const z = (e.pageX + f.x) * 0.5, u = (e.pageY + f.y) * 0.5;
    this._updateZoomParameters(z, u);
  }
  _handleTouchMoveDollyPan(e) {
    this.enableZoom && this._handleTouchMoveDolly(e), this.enablePan && this._handleTouchMovePan(e);
  }
  _handleTouchMoveDollyRotate(e) {
    this.enableZoom && this._handleTouchMoveDolly(e), this.enableRotate && this._handleTouchMoveRotate(e);
  }
  _addPointer(e) {
    this._pointers.push(e.pointerId);
  }
  _removePointer(e) {
    delete this._pointerPositions[e.pointerId];
    for (let f = 0; f < this._pointers.length; f++) if (this._pointers[f] == e.pointerId) {
      this._pointers.splice(f, 1);
      return;
    }
  }
  _isTrackingPointer(e) {
    for (let f = 0; f < this._pointers.length; f++) if (this._pointers[f] == e.pointerId) return true;
    return false;
  }
  _trackPointer(e) {
    let f = this._pointerPositions[e.pointerId];
    f === void 0 && (f = new Me(), this._pointerPositions[e.pointerId] = f), f.set(e.pageX, e.pageY);
  }
  _getSecondPointerPosition(e) {
    const f = e.pointerId === this._pointers[0] ? this._pointers[1] : this._pointers[0];
    return this._pointerPositions[f];
  }
  _customWheelEvent(e) {
    const f = e.deltaMode, n = { clientX: e.clientX, clientY: e.clientY, deltaY: e.deltaY };
    switch (f) {
      case 1:
        n.deltaY *= 16;
        break;
      case 2:
        n.deltaY *= 100;
        break;
    }
    return e.ctrlKey && !this._controlActive && (n.deltaY *= 10), n;
  }
}
function OSt(t) {
  this.enabled !== false && (this._pointers.length === 0 && (this.domElement.setPointerCapture(t.pointerId), this.domElement.addEventListener("pointermove", this._onPointerMove), this.domElement.addEventListener("pointerup", this._onPointerUp)), !this._isTrackingPointer(t) && (this._addPointer(t), t.pointerType === "touch" ? this._onTouchStart(t) : this._onMouseDown(t)));
}
function HSt(t) {
  this.enabled !== false && (t.pointerType === "touch" ? this._onTouchMove(t) : this._onMouseMove(t));
}
function NSt(t) {
  switch (this._removePointer(t), this._pointers.length) {
    case 0:
      this.domElement.releasePointerCapture(t.pointerId), this.domElement.removeEventListener("pointermove", this._onPointerMove), this.domElement.removeEventListener("pointerup", this._onPointerUp), this.dispatchEvent(DE), this.state = Xn.NONE;
      break;
    case 1:
      const e = this._pointers[0], f = this._pointerPositions[e];
      this._onTouchStart({ pointerId: e, pageX: f.x, pageY: f.y });
      break;
  }
}
function MSt(t) {
  let e;
  switch (t.button) {
    case 0:
      e = this.mouseButtons.LEFT;
      break;
    case 1:
      e = this.mouseButtons.MIDDLE;
      break;
    case 2:
      e = this.mouseButtons.RIGHT;
      break;
    default:
      e = -1;
  }
  switch (e) {
    case Fa.DOLLY:
      if (this.enableZoom === false) return;
      this._handleMouseDownDolly(t), this.state = Xn.DOLLY;
      break;
    case Fa.ROTATE:
      if (t.ctrlKey || t.metaKey || t.shiftKey) {
        if (this.enablePan === false) return;
        this._handleMouseDownPan(t), this.state = Xn.PAN;
      } else {
        if (this.enableRotate === false) return;
        this._handleMouseDownRotate(t), this.state = Xn.ROTATE;
      }
      break;
    case Fa.PAN:
      if (t.ctrlKey || t.metaKey || t.shiftKey) {
        if (this.enableRotate === false) return;
        this._handleMouseDownRotate(t), this.state = Xn.ROTATE;
      } else {
        if (this.enablePan === false) return;
        this._handleMouseDownPan(t), this.state = Xn.PAN;
      }
      break;
    default:
      this.state = Xn.NONE;
  }
  this.state !== Xn.NONE && this.dispatchEvent(_M);
}
function iSt(t) {
  switch (this.state) {
    case Xn.ROTATE:
      if (this.enableRotate === false) return;
      this._handleMouseMoveRotate(t);
      break;
    case Xn.DOLLY:
      if (this.enableZoom === false) return;
      this._handleMouseMoveDolly(t);
      break;
    case Xn.PAN:
      if (this.enablePan === false) return;
      this._handleMouseMovePan(t);
      break;
  }
}
function pSt(t) {
  this.enabled === false || this.enableZoom === false || this.state !== Xn.NONE || (t.preventDefault(), this.dispatchEvent(_M), this._handleMouseWheel(this._customWheelEvent(t)), this.dispatchEvent(DE));
}
function TSt(t) {
  this.enabled !== false && this._handleKeyDown(t);
}
function oSt(t) {
  switch (this._trackPointer(t), this._pointers.length) {
    case 1:
      switch (this.touches.ONE) {
        case Ra.ROTATE:
          if (this.enableRotate === false) return;
          this._handleTouchStartRotate(t), this.state = Xn.TOUCH_ROTATE;
          break;
        case Ra.PAN:
          if (this.enablePan === false) return;
          this._handleTouchStartPan(t), this.state = Xn.TOUCH_PAN;
          break;
        default:
          this.state = Xn.NONE;
      }
      break;
    case 2:
      switch (this.touches.TWO) {
        case Ra.DOLLY_PAN:
          if (this.enableZoom === false && this.enablePan === false) return;
          this._handleTouchStartDollyPan(t), this.state = Xn.TOUCH_DOLLY_PAN;
          break;
        case Ra.DOLLY_ROTATE:
          if (this.enableZoom === false && this.enableRotate === false) return;
          this._handleTouchStartDollyRotate(t), this.state = Xn.TOUCH_DOLLY_ROTATE;
          break;
        default:
          this.state = Xn.NONE;
      }
      break;
    default:
      this.state = Xn.NONE;
  }
  this.state !== Xn.NONE && this.dispatchEvent(_M);
}
function wSt(t) {
  switch (this._trackPointer(t), this.state) {
    case Xn.TOUCH_ROTATE:
      if (this.enableRotate === false) return;
      this._handleTouchMoveRotate(t), this.update();
      break;
    case Xn.TOUCH_PAN:
      if (this.enablePan === false) return;
      this._handleTouchMovePan(t), this.update();
      break;
    case Xn.TOUCH_DOLLY_PAN:
      if (this.enableZoom === false && this.enablePan === false) return;
      this._handleTouchMoveDollyPan(t), this.update();
      break;
    case Xn.TOUCH_DOLLY_ROTATE:
      if (this.enableZoom === false && this.enableRotate === false) return;
      this._handleTouchMoveDollyRotate(t), this.update();
      break;
    default:
      this.state = Xn.NONE;
  }
}
function hSt(t) {
  this.enabled !== false && t.preventDefault();
}
function GSt(t) {
  t.key === "Control" && (this._controlActive = true, this.domElement.getRootNode().addEventListener("keyup", this._interceptControlUp, { passive: true, capture: true }));
}
function VSt(t) {
  t.key === "Control" && (this._controlActive = false, this.domElement.getRootNode().removeEventListener("keyup", this._interceptControlUp, { passive: true, capture: true }));
}
const FE = [{ id: "E5_F5_DRAWN_001", label: "\u5178\u578B\u7A7A\u8C03\u5916\u673A", system: "hvac", floor: "F5", center: [-31.54887104034424, 16.586525440216064, -17.414795875549316], bounds: [[-32.18381881713867, 15.813525199890137, -18.1306095123291], [-30.913923263549805, 17.359525680541992, -16.69898223876953]], sources: ["E5_F5_DRAWN_001", "E5_F5_DRAWN_001_Fan_-1", "E5_F5_DRAWN_001_Fan_1", "E5_F5_DRAWN_001_Feed", "E5_F5_DRAWN_001_Foot_-1", "E5_F5_DRAWN_001_Foot_1"], parts: [{ material: "E5_Body", start: 0, count: 36 }, { material: "E5_Fan", start: 0, count: 72 }, { material: "E5_Fan", start: 72, count: 72 }, { material: "E6_Organized_Cable_Conduit", start: 0, count: 36 }, { material: "E5_Foot", start: 0, count: 36 }, { material: "E5_Foot", start: 36, count: 36 }] }, { id: "HVAC-INDOOR-DEMO", label: "\u5178\u578B\u7A7A\u8C03\u5185\u673A\uFF08\u793A\u610F\uFF09", system: "hvac", floor: "F3", center: [-29.10259437561035, 11.024999618530273, -17.06861686706543], bounds: [[-29.702594375610353, 10.824999618530274, -17.66861686706543], [-28.50259437561035, 11.224999618530273, -16.46861686706543]], sources: ["F3-RF-Port-039"], parts: [] }, { id: "LIGHT-TYPICAL", label: "\u5178\u578B\u7167\u660E\u706F\u5177", system: "lighting", floor: "F1", center: [-74.65775, 3.455, -4.2976], bounds: [[-74.7091, 3.446, -4.8005], [-74.6064, 3.464, -3.7947]], sources: ["F1_Facade_Interior_Luminaires"], parts: [{ material: "R_Interior_linear_light_0", start: 0, count: 3 }, { material: "R_Interior_linear_light_0", start: 3, count: 3 }, { material: "R_Interior_linear_light_0", start: 12, count: 3 }, { material: "R_Interior_linear_light_0", start: 15, count: 3 }, { material: "R_Interior_linear_light_0", start: 18, count: 3 }, { material: "R_Interior_linear_light_0", start: 24, count: 3 }, { material: "R_Interior_linear_light_0", start: 27, count: 3 }, { material: "R_Interior_linear_light_0", start: 30, count: 3 }, { material: "R_Interior_linear_light_0", start: 33, count: 3 }, { material: "R_Interior_linear_light_0", start: 6, count: 3 }, { material: "R_Interior_linear_light_0", start: 9, count: 3 }, { material: "R_Interior_linear_light_0", start: 21, count: 3 }] }, { id: "FIRE-TYPICAL", label: "\u5178\u578B\u6D88\u9632\u914D\u7535\u6865\u67B6", system: "fire", floor: "F5", center: [-48.136695861816406, 16.65999984741211, -6.375421047210693], bounds: [[-48.32339096069336, 16.610000610351562, -8.06184196472168], [-47.95000076293945, 16.709999084472656, -4.689000129699707]], sources: ["E3_Tray_F5_CABLETRAY_FIRE_026"], parts: [{ material: "E2_Tray_Fire", start: 5124, count: 216 }] }];
const Z1 = wI.slice(0, 1);
// Recover disconnected authored light bodies, welding face vertices before grouping.
// Their positions are model evidence; replacement fixture details and branches are visual demonstration only.
function lightingFixtures(geometries) {
  const floors = Array.from({ length: 6 }, () => []);
  const heights = [3.455, 7.255, 11.055, 14.855, 18.655, 22.655];
  for (const [name, geometry] of geometries) {
    if (!/^R_Interior_linear_light_/.test(name) || !geometry.index) continue;
    const positions = geometry.getAttribute('position'), indices = geometry.index.array;
    const parent = Array.from({ length: positions.count }, (_, index) => index), welded = new Map();
    const root = index => { while (parent[index] !== index) { parent[index] = parent[parent[index]]; index = parent[index]; } return index; };
    const join = (left, right) => { parent[root(left)] = root(right); };
    for (let index = 0; index < positions.count; index++) {
      const key = [positions.getX(index), positions.getY(index), positions.getZ(index)].map(value => Math.round(value * 10000)).join(':');
      if (welded.has(key)) join(index, welded.get(key)); else welded.set(key, index);
    }
    for (let index = 0; index < indices.length; index += 3) { join(indices[index], indices[index + 1]); join(indices[index], indices[index + 2]); }
    const components = new Map();
    for (const index of welded.values()) {
      const key = root(index), points = components.get(key) || [];
      points.push(new ht().fromBufferAttribute(positions, index)); components.set(key, points);
    }
    for (const points of components.values()) {
      const box = new c9().setFromPoints(points), center = box.getCenter(new ht()), size = box.getSize(new ht());
      if (points.length < 4 || size.length() > 4 || size.length() < .15) continue;
      const floor = heights.reduce((best, height, index) => Math.abs(height - center.y) < Math.abs(heights[best] - center.y) ? index : best, 0);
      let xx = 0, zz = 0, xz = 0;
      for (const point of points) { const dx = point.x - center.x, dz = point.z - center.z; xx += dx * dx; zz += dz * dz; xz += dx * dz; }
      floors[floor].push({ center, angle: -.5 * Math.atan2(2 * xz, xx - zz) });
    }
  }
  return floors;
}
function YSt(t, e, f, n, v, r, z, hvac = null) {
  const u = t.background, b = new D3(), a = new D3();
  t.add(b), t.add(a), b.visible = false, a.visible = false;
  const P = t.children.filter((B) => B !== b && B !== a && !f.includes(B) && !B.isLight), s = f.map((B) => {
    const et = B.material;
    return { mesh: B, m: et, opacity: et.opacity, transparent: et.transparent, depthWrite: et.depthWrite };
  }), d = /* @__PURE__ */ new Map(), c = [], l = [], W = [...Z1, ...FE.filter(asset => asset.system !== "hvac" && asset.system !== "lighting"), ];
  let X = 0, y = null, m = null, j = 0, x = !matchMedia("(prefers-reduced-motion: reduce)").matches, L = 0, O = "normal", N = "\u5168\u90E8";
  const i = /* @__PURE__ */ new Map();
  let H = () => {
  };
  const M = matchMedia("(prefers-reduced-motion: reduce)").matches;
  function p(B, et, nt, I) {
    for (const U of B) {
      const J = n.get(U.material);
      if (!(J != null && J.index)) continue;
      const g = new bv();
      g.setAttribute("position", J.getAttribute("position")), g.setAttribute("normal", J.getAttribute("normal")), g.setIndex(new ov(J.index.array.slice(U.start, U.start + U.count), 1)), et.userData.baseColor = et.color.clone();
      const tt = new Zn(g, et);
      I && (tt.userData.assetId = I, c.push(tt)), nt.add(tt);
    }
  }
  const T = [], w = (B) => {
    const et = new j6({ color: "#8ca4bd", transparent: true, opacity: B, depthWrite: false, side: $3 });
    return et.forceSinglePass = true, T.push({ material: et, opacity: B }), et;
  };
  for (const B of XDt) {
    const et = new D3();
    p(B.parts, w(0.065), et);
    for (const nt of [...et.children]) {
      const I = nt;
      a.add(I);
      const U = new MFt(I.geometry, 25), J = U.getAttribute("position"), g = new iM({ color: "#6f879e", transparent: true, opacity: 0.22, depthWrite: false });
      T.push({ material: g, opacity: 0.22 }), a.add(new qK(U, g));
      let tt = -1 / 0;
      for (let ut = 0; ut < J.count; ut++) tt = Math.max(tt, J.getY(ut));
      const zt = [];
      for (let ut = 0; ut < J.count; ut += 2) {
        const Pt = new ht().fromBufferAttribute(J, ut), xt = new ht().fromBufferAttribute(J, ut + 1);
        if (Math.abs(Pt.y - tt) > 0.025 || Math.abs(xt.y - tt) > 0.025 || Pt.distanceTo(xt) < 0.1) continue;
        const Lt = B.id === "F1_Slab" ? 4.2 : 3.8;
        zt.push(Pt.x, Pt.y, Pt.z, xt.x, xt.y, xt.z, xt.x, xt.y + Lt, xt.z, Pt.x, Pt.y, Pt.z, xt.x, xt.y + Lt, xt.z, Pt.x, Pt.y + Lt, Pt.z);
      }
      if (zt.length) {
        const ut = new bv();
        ut.setAttribute("position", new ln(zt, 3)), a.add(new Zn(ut, w(0.032)));
      }
    }
  }
  // Reuse the authored facade/roof geometry: the elevator context must still look like this building.
  const slabContextMaterials = new Set(T.map(entry => entry.material));
  const buildingShell = new D3(), buildingSurfaces = [];
  for (const [name, geometry] of n) {
    if (!/^(M_White_aluminium|M_Roof_membrane|Photo_Charcoal_Aluminium|Photo_Clear_Entrance_Glass|Front_Photo|Rear_Photo|R_Window_room|M_Charcoal_plinth|Platform_Continuous_Limestone)/.test(name)) continue;
    const material = w(/roof/i.test(name) ? 0.32 : /window|glass|charcoal/i.test(name) ? 0.12 : 0.28);
    const mesh = new Zn(geometry, material);
    buildingShell.add(mesh);
    buildingSurfaces.push(geometry.getAttribute("position").array);
  }
  a.add(buildingShell);
  let elevatorViewport = '';
  function buildingContext() {
    const overview = z(), originalTarget = new ht(...overview.target);
    const back = new ht(...overview.position).sub(originalTarget), originalDistance = back.length();
    back.normalize();
    const right = new ht().crossVectors(new ht(0, 1, 0), back).normalize();
    if (right.lengthSq() < 0.5) right.set(1, 0, 0);
    const up = new ht().crossVectors(back, right).normalize();
    const lift = Z1[0], liftCenter = new ht(lift.center[0], (lift.floors.at(-1).y + 3.1) / 2, lift.center[2]);
    const rect = v.getBoundingClientRect(), host = v.closest('.building-screen');
    const fraction = (selector, axis) => {
      const box = host?.querySelector(selector)?.getBoundingClientRect();
      return box?.[axis] ? box[axis] / Math.max(1, rect[axis]) : 0;
    };
    const safeX = Math.max(.2, 1 - 2 * Math.max(.15, fraction('.asset-list', 'width') + .015, fraction('.asset-details', 'width') + .015));
    const safeY = Math.max(.2, 1 - 2 * Math.max(.12, fraction('.professional-header', 'height') + fraction('.professional-tabs', 'height') + .025, fraction('.professional-bottom', 'height') + .025));
    const tanY = Math.tan(overview.span * Math.PI / 360), tanX = tanY * Math.max(.1, v.clientWidth / Math.max(1, v.clientHeight));
    let target, distance;
    // Start with an elevator-led composition and a modest dolly-in. Reduce the lateral shift
    // if an actual facade vertex would be clipped; empty bounding-box corners do not limit the view.
    for (const weight of [.55, .4, .25, 0]) {
      target = originalTarget.clone().lerp(liftCenter, weight);
      let required = 1;
      for (const points of buildingSurfaces) for (let index = 0; index < points.length; index += 3) {
        const px = points[index] - target.x, py = points[index + 1] - target.y, pz = points[index + 2] - target.z;
        const depth = px * back.x + py * back.y + pz * back.z;
        required = Math.max(required,
          depth + Math.abs(px * right.x + py * right.y + pz * right.z) / (tanX * safeX * .97),
          depth + Math.abs(px * up.x + py * up.y + pz * up.z) / (tanY * safeY * .97));
      }
      distance = Math.max(originalDistance * .84, required);
      if (distance <= originalDistance * .98) break;
    }
    const position = target.clone().addScaledVector(back, distance);
    r(position.toArray(), target.toArray(), overview.span);
    elevatorViewport = v.clientWidth + ':' + v.clientHeight;
  }
  function Y(B, et, nt, I, U) {
    const J = new Zn(new Lz(...et), new x7({ color: I, metalness: 0.4, roughness: 0.38 }));
    return J.position.fromArray(nt), B.add(J), U && (J.userData.assetId = U, c.push(J)), J;
  }
  function q(B, et, nt) {
    const I = document.createElement("canvas");
    const floorLabel = /^F\d+$/.test(B);
    I.width = floorLabel ? 128 : 256, I.height = 64;
    const U = I.getContext("2d");
    U.fillStyle = "rgba(244,249,252,.96)", U.fillRect(0, 0, I.width, 64), U.fillStyle = "#143f64", U.font = floorLabel ? "bold 40px sans-serif" : "bold 36px sans-serif", U.textAlign = "center", U.fillText(B, I.width / 2, 46);
    const J = new TM(I);
    J.colorSpace = Qv;
    const g = new mFt(new MK({ map: J, depthTest: false }));
    return g.position.fromArray(et), g.scale.set(5, 1.25, 1), nt.add(g), g;
  }
  const D = new D3();
  b.add(D), d.set("elevator", D), Z1.forEach((B, et) => {
    const nt = new D3();
    nt.position.fromArray(B.center), D.add(nt), nt.userData.assetId = B.id;
    const I = B.floors.at(-1).y + 3.1, U = new Zn(new Lz(2.2, I, 2.2), new j6({ color: "#4c91bd", transparent: true, opacity: 0.16, depthWrite: false }));
    U.position.y = I / 2, nt.add(U);
    for (const zt of [-1, 1]) for (const ut of [-1, 1]) Y(nt, [0.14, I, 0.14], [zt, I / 2, ut], "#186db3", B.id);
    B.floors.forEach((zt) => {
      for (const Pt of [-1.05, 1.05]) Y(nt, [2.2, 0.045, 0.045], [0, zt.y, Pt], "#86a6b7");
    }), q("电梯", [0, I + 1.3 + et % 2 * 1.4, 0], nt);
    const J = new D3();
    nt.add(J), Y(J, [1.9, 0.13, 1.9], [0, 0.065, 0], "#647e8c", B.id), Y(J, [1.9, 0.1, 1.9], [0, 2.3, 0], "#7e9ca9", B.id), Y(J, [1.9, 2.3, 0.08], [0, 1.15, -0.92], "#d1dbe0", B.id);
    for (const zt of [-0.93, 0.93]) Y(J, [0.07, 2.3, 1.85], [zt, 1.15, 0], "#91aebc", B.id);
    const g = [Y(J, [0.88, 2.18, 0.06], [-0.45, 1.17, 0.94], "#328bb8", B.id), Y(J, [0.88, 2.18, 0.06], [0.45, 1.17, 0.94], "#328bb8", B.id)], tt = Y(J, [1.65, 0.08, 0.08], [0, 2.18, 0.99], "#32c0bb", B.id);
    l.push({ car: J, doors: g, marker: tt });
  });
  D.traverse(node => {
    if (!node.isMesh) return;
    node.renderOrder = 20;
    node.material.transparent = true;
    node.material.depthTest = false;
    node.material.depthWrite = false;
    if (node.material.emissive) { node.material.emissive.copy(node.material.color); node.material.emissiveIntensity = 0.45; }
  });
  const V = new XRt(new c9(new ht(), new ht(1, 1, 1)), new Nf("#2079be"));
  V.visible = false, b.add(V);
  for (const B of ["fire"]) {
    const et = new D3();
    b.add(et), d.set(B, et);
    for (const nt of FE.filter((I) => I.system === B)) {
      const I = new D3();
      if (et.add(I), i.set(nt.id, I), nt.id === "HVAC-INDOOR-DEMO") {
        const U = nt.center;
        I.position.fromArray(U), Y(I, [1.2, 0.3, 1.2], [0, 0, 0], "#dce7eb", nt.id), Y(I, [0.75, 0.025, 0.75], [0, -0.16, 0], "#4b6572", nt.id), Y(I, [0.85, 0.02, 0.85], [0, 0.16, 0], "#526d79", nt.id);
        for (let J = 0; J < 10; J++) Y(I, [0.78, 0.025, 0.025], [0, 0.18, -0.35 + J * 0.078], "#b0c4cc", nt.id);
        for (let J = 0; J < 8; J++) Y(I, [0.085, 0.08, 0.025], [-0.4 + J * 0.115, -0.03, 0.61], "#5d7b8b", nt.id);
        for (const J of [-0.51, 0.51]) for (let g = 0; g < 7; g++) Y(I, [0.12, 0.035, 0.07], [J, -0.18, -0.35 + g * 0.115], "#7594a1", nt.id);
        Y(I, [0.1, 0.02, 0.04], [0.35, -0.18, 0.45], "#39ada6", nt.id), I.traverse((J) => {
          if (J.isMesh) {
            const g = J.material;
            g.userData.baseColor = g.color.clone();
          }
        });
      }
      for (const U of nt.parts) {
        const J = B === "lighting" ? "#b17412" : B === "fire" ? "#b85d43" : /fan/i.test(U.material) ? "#344954" : /foot|support/i.test(U.material) ? "#829ba4" : "#b4cbcd";
        p([U], new x7({ color: J, roughness: 0.45, metalness: 0.3, emissive: B === "lighting" ? "#a96c0a" : "#000000", emissiveIntensity: B === "lighting" ? 0.8 : 0 }), I, nt.id);
      }
    }
  }
  const lighting = new D3(), lightingFloors = [], floorLabels = [];
  b.add(lighting); d.set('lighting', lighting);
  const floorCallouts = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  floorCallouts.setAttribute('aria-label', '照明楼层标识');
  floorCallouts.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;pointer-events:none;display:none;overflow:hidden';
  v.appendChild(floorCallouts);
  const calloutNode = (tag, attributes, parent) => {
    const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
    for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, value);
    parent.appendChild(node); return node;
  };
  const addFloorCallout = (topic, floor, floorIndex, group, anchors) => {
    const row = calloutNode('g', {}, floorCallouts);
    const line = calloutNode('polyline', { fill: 'none', stroke: '#718da6', 'stroke-width': '1.2' }, row);
    const dot = calloutNode('circle', { r: '2.5', fill: '#3c709b' }, row);
    const box = calloutNode('rect', { width: topic === 'elevator' ? '52' : '82', height: '30', rx: '5', fill: '#f4f8fc', 'fill-opacity': '.95', stroke: '#bccddd' }, row);
    const label = calloutNode('text', { fill: '#234e75', 'font-size': '13', 'font-family': 'system-ui,sans-serif', 'font-weight': '600', 'text-anchor': 'middle', 'dominant-baseline': 'central' }, row);
    label.textContent = topic === 'elevator' ? floor : floor + ' · 照明';
    floorLabels.push({ row, line, dot, box, label, group, floorIndex, anchors, topic });
  };
  Z1.forEach((lift, index) => lift.floors.forEach((floor, floorIndex) => {
    addFloorCallout('elevator', floor.name, floorIndex, D.children[index], [new ht(lift.center[0] - 1.05, lift.center[1] + floor.y, lift.center[2])]);
  }));
  const lightBodyGeometry = new Lz(1, 1, 1);
  const lightBodyMaterial = new x7({ color: '#4f6675', metalness: .55, roughness: .32 });
  const lightRimMaterial = new j6({ color: '#dce5e9' });
  const lightFaceMaterial = new j6({ color: '#fff0b9', toneMapped: false });
  const lightGlowMaterial = new j6({ color: '#ffd77a', transparent: true, opacity: .075, depthWrite: false });
  for (const [floorIndex, fixtures] of lightingFixtures(n).entries()) {
    if (!fixtures.length) continue;
    const floor = 'F' + (floorIndex + 1), id = 'LIGHT-DEMO-' + floor, group = new D3();
    const bounds = new c9().setFromPoints(fixtures.map(fixture => fixture.center));
    const asset = { id, label: floor + ' 照明灯具（演示）', floor, system: 'lighting', center: bounds.getCenter(new ht()).toArray(), bounds: [bounds.min.toArray(), bounds.max.toArray()], fixtureCount: fixtures.length };
    W.push(asset); lighting.add(group); lightingFloors.push({ group, asset });
    // Instancing keeps six floors inexpensive. Layered housing, rim and diffuser are visible up close;
    // a restrained translucent halo keeps the same fixtures legible in the whole-building view.
    const batches = [lightBodyMaterial, lightRimMaterial, lightFaceMaterial, lightGlowMaterial].map((material, layer) => {
      const mesh = new ks(lightBodyGeometry, material, fixtures.length);
      mesh.userData.assetId = id; mesh.renderOrder = layer === 3 ? 5 : 10 + layer;
      group.add(mesh); if (layer === 0) c.push(mesh); return mesh;
    });
    const transform = new D3();
    fixtures.forEach((fixture, index) => {
      const panel = index % 3 === 0, length = panel ? 1.15 : 1.9, width = panel ? .85 : .24;
      const sizes = [[length, .18, width], [length - .06, .025, width - .04], [length - .14, .035, width - .1], [length + .65, .012, width + .55]];
      sizes.forEach((size, layer) => {
        transform.position.copy(fixture.center); transform.position.y += [0, .105, .125, -.13][layer];
        transform.rotation.set(0, fixture.angle, 0); transform.scale.fromArray(size); transform.updateMatrix();
        batches[layer].setMatrixAt(index, transform.matrix);
      });
    });
    for (const mesh of batches) { mesh.instanceMatrix.needsUpdate = true; mesh.computeBoundingSphere(); }
    // Short nearest-neighbour branches convey a system without drawing long wires across courtyards.
    // They are schematic display connections, not electrical circuit records.
    const connections = [], edges = new Set();
    for (let index = 0; index < fixtures.length; index++) {
      const from = fixtures[index].center; let nearest = -1, distance = 7;
      for (let other = 0; other < fixtures.length; other++) {
        if (index === other) continue;
        const candidate = from.distanceTo(fixtures[other].center);
        if (candidate < distance && candidate > .4) { distance = candidate; nearest = other; }
      }
      if (nearest < 0) continue;
      const key = [Math.min(index, nearest), Math.max(index, nearest)].join(':');
      if (edges.has(key)) continue; edges.add(key);
      const to = fixtures[nearest].center;
      connections.push(from.x, from.y - .16, from.z, to.x, to.y - .16, to.z);
    }
    const lineGeometry = new bv(); lineGeometry.setAttribute('position', new ln(connections, 3));
    group.add(new qK(lineGeometry, new iM({ color: '#b99b55', transparent: true, opacity: .32, depthWrite: false })));
    addFloorCallout('lighting', floor, floorIndex, group, fixtures.map(fixture => fixture.center));
  }
  function updateFloorCallouts() {
    floorCallouts.style.display = y === 'lighting' || y === 'elevator' ? 'block' : 'none';
    if (y !== 'lighting' && y !== 'elevator') return;
    floorCallouts.setAttribute('aria-label', y === 'elevator' ? '电梯楼层标识' : '照明楼层标识');
    const width = v.clientWidth, height = v.clientHeight, rect = v.getBoundingClientRect();
    const panel = v.closest('.building-screen')?.querySelector('.asset-list')?.getBoundingClientRect();
    const displayScale = rect.width > 0 ? rect.width / Math.max(1, width) : 1;
    const leftLimit = Math.max(12, panel ? (panel.right - rect.left) / displayScale + 12 : 12);
    const rows = [];
    for (const item of floorLabels) {
      if (item.topic !== y) { item.row.style.display = 'none'; continue; }
      const projected = item.anchors.map(point => point.clone().project(e)).filter(point => point.z > -1 && point.z < 1 && Math.abs(point.x) < 1 && Math.abs(point.y) < 1);
      item.row.style.display = item.group.visible && projected.length ? '' : 'none';
      if (!item.group.visible || !projected.length) continue;
      const anchor = projected.reduce((left, point) => point.x < left.x ? point : left);
      rows.push({ item, x: (anchor.x + 1) * width / 2, y: (1 - anchor.y) * height / 2 });
    }
    rows.sort((left, right) => right.item.floorIndex - left.item.floorIndex);
    if (!rows.length) return;
    const column = Math.max(leftLimit, Math.min(...rows.map(row => row.x)) - (y === 'elevator' ? 150 : 116));
    const labelWidth = y === 'elevator' ? 52 : 82;
    const gap = Math.min(44, Math.max(32, (height - 170) / 6));
    const middle = rows.reduce((sum, row) => sum + row.y, 0) / rows.length;
    const top = Math.max(95, Math.min(height - 65 - (rows.length - 1) * gap, middle - (rows.length - 1) * gap / 2));
    // Labels stay in screen space; only the leaders follow the projected floor positions.
    rows.forEach(({ item, x, y }, index) => {
      const cy = top + index * gap;
      item.box.setAttribute('x', column); item.box.setAttribute('y', cy - 15);
      item.label.setAttribute('x', column + labelWidth / 2); item.label.setAttribute('y', cy);
      item.line.setAttribute('points', `${column + labelWidth},${cy} ${column + labelWidth + 12},${cy} ${x},${y}`);
      item.dot.setAttribute('cx', x); item.dot.setAttribute('cy', y);
    });
  }
  let lightingViewport = '';
  function lightingContext() {
    const overview = z(), center = new ht(...overview.target), back = new ht(...overview.position).sub(center);
    back.y = Math.hypot(back.x, back.z) * .38; back.normalize();
    const right = new ht().crossVectors(new ht(0, 1, 0), back).normalize(), up = new ht().crossVectors(back, right).normalize();
    const host = v.closest('.building-screen'), width = Math.max(1, v.clientWidth), height = Math.max(1, v.clientHeight);
    const displayWidth = v.getBoundingClientRect().width || width;
    const panel = selector => (host?.querySelector(selector)?.getBoundingClientRect().width || 0) / displayWidth;
    const safeX = Math.max(.2, 1 - 2 * Math.max(.15, panel('.asset-list') + .01, panel('.asset-details') + .01));
    const tanY = Math.tan(40 * Math.PI / 360), tanX = tanY * width / height;
    let distance = 1;
    for (const points of buildingSurfaces) for (let index = 0; index < points.length; index += 3) {
      const delta = new ht(points[index], points[index + 1], points[index + 2]).sub(center), depth = delta.dot(back);
      distance = Math.max(distance, depth + Math.abs(delta.dot(right)) / (tanX * safeX * .98), depth + Math.abs(delta.dot(up)) / (tanY * .78));
    }
    r(center.clone().addScaledVector(back, distance).toArray(), center.toArray(), 40);
    lightingViewport = width + ':' + height;
  }
  const hvacFloor = new D3(), hvacBounds = new c9();
  b.add(hvacFloor); d.set("hvac", hvacFloor);
  const hvacLabels = [];
  function setHvacData(data) {
    if (hvac) return;
    hvac = data;
    W.push(...data.units.map(unit => ({ id: unit.id, label: unit.id + " · " + unit.name, system: "hvac", floor: "F3", center: [unit.xyz[0], unit.xyz[2], -unit.xyz[1]], bounds: null, kind: unit.type })));
  for (const entry of hvac.meshes) {
    const name = entry.material, equipment = entry.group === "units", services = entry.group === "services";
    const color = equipment ? (/Grille/.test(name) ? "#174774" : "#247cb4") : services ? (/RF_/.test(name) ? "#0879ad" : /Drain/.test(name) ? "#269b91" : /Duct/.test(name) ? "#4c87b0" : "#899eaf") : /glass/i.test(name) ? "#aac5d7" : /Stone|floor/.test(name) ? "#dae4e9" : "#bdcdd8";
    const opacity = equipment || services ? 1 : /glass/i.test(name) ? .18 : /Stone|floor/.test(name) ? .96 : .62;
    const material = new j6({ color, transparent: true, opacity, depthWrite: !equipment && !services, depthTest: !equipment && !services, side: $3 });
    const mesh = new Zn(entry.geometry, material); mesh.renderOrder = equipment ? 12 : services ? 10 : 2;
    hvacFloor.add(mesh);
    hvacBounds.expandByPoint(new ht(...entry.bounds[0])); hvacBounds.expandByPoint(new ht(...entry.bounds[1]));
  }
  for (const id of ["F3-IDU-001", "F3-IDU-038", "F3-IDU-051", "F3-IDU-100"]) {
    const unit = hvac?.units.find(unit => unit.id === id);
    if (!unit) continue;
    const anchor = new ht(unit.xyz[0], unit.xyz[2], -unit.xyz[1]);
    const label = q("空调内机", anchor.toArray(), hvacFloor);
    label.renderOrder = 32; label.material.fog = false; label.userData.hvacUnit = id;
    const geometry = new bv().setFromPoints([anchor, anchor]);
    const leader = new qK(geometry, new iM({ color: "#246998", transparent: true, opacity: .85, depthTest: false, depthWrite: false }));
    leader.renderOrder = 31; hvacFloor.add(leader);
    hvacLabels.push({ id, anchor, label, leader });
  }
  }
  function hvacContext() {
    if (hvacBounds.isEmpty()) return;
    const center = hvacBounds.getCenter(new ht());
    const overview = z(), back = new ht(...overview.position).sub(new ht(...overview.target));
    back.y = Math.hypot(back.x, back.z) * .95; back.normalize();
    const right = new ht().crossVectors(new ht(0, 1, 0), back).normalize(), up = new ht().crossVectors(back, right).normalize();
    const tanY = Math.tan(40 * Math.PI / 360), tanX = tanY * Math.max(.1, v.clientWidth / Math.max(1, v.clientHeight));
    const rect = v.getBoundingClientRect(), host = v.closest('.building-screen');
    const panelWidth = selector => (host?.querySelector(selector)?.getBoundingClientRect().width || 0) / Math.max(1, rect.width);
    const horizontal = Math.max(.2, 1 - 2 * Math.max(.08, panelWidth('.asset-list') + .01, panelWidth('.asset-details') + .01));
    let distance = 1;
    // Frame the repaired floor's actual surfaces, with a tilted 2.5D view; never focus a prototype box.
    for (const entry of hvac.meshes) {
      const points = entry.geometry.getAttribute("position").array;
      for (let index = 0; index < points.length; index += 3) {
        const px = points[index] - center.x, py = points[index + 1] - center.y, pz = points[index + 2] - center.z;
        const depth = px * back.x + py * back.y + pz * back.z;
        distance = Math.max(distance, depth + Math.abs(px * right.x + py * right.y + pz * right.z) / (tanX * horizontal * .985), depth + Math.abs(px * up.x + py * up.y + pz * up.z) / (tanY * .84));
      }
    }
    r(center.clone().addScaledVector(back, distance).toArray(), center.toArray(), 40);
  }
  function G() {
    if (y === "lighting") { lightingContext(); return; }
    if (y === "hvac") { hvacContext(); return; }
    if (y === "elevator") {
      buildingContext();
      return;
    }
    const B = new c9();
    for (const U of W.filter((J) => J.system === y)) U.bounds && (B.expandByPoint(new ht().fromArray(U.bounds[0])), B.expandByPoint(new ht().fromArray(U.bounds[1])));
    const et = B.getCenter(new ht()), nt = B.getSize(new ht()), I = Math.max(5, nt.length() * 1.5);
    r([et.x + I * 0.8, et.y + I * 0.6, et.z + I * 1.15], et.toArray(), 43);
  }
  function R(B) {
    var nt, I;
    X++, m = B;
    const et = W.find((U) => U.id === B);
    if (et) {
      const U = et.center, J = et.system === "elevator" ? ((((I = (nt = et.floors) == null ? void 0 : nt.at(-1)) == null ? void 0 : I.y) || 12) + 3.1) / 2 + 1 : U[1], g = et.system === "elevator" ? 35 : Math.max(7, ...et.bounds ? et.bounds[1].map((tt, zt) => tt - et.bounds[0][zt]) : [20]) * 1.25;
      r([U[0] + g * 0.8, J + g * 0.6, U[2] + g * 1.15], [U[0], J, U[2]], 43), et.bounds ? (V.box.set(new ht().fromArray(et.bounds[0]), new ht().fromArray(et.bounds[1])), V.visible = true) : V.visible = false;
    } else V.visible = false, G();
  }
  function k(B) {
    if (!B) {
      y = null, m = null, H();
      return;
    }
    y = B, t.background = new Nf("#e9f1f6"), N = "\u5168\u90E8", O = "normal", i.forEach((et) => et.visible = true), b.visible = true, P.forEach((et) => et.visible = false), d.forEach((et, nt) => et.visible = nt === B), m = null, R(null);
    if (B === "hvac") N = "F3";
    elevatorViewport = ""; lightingViewport = "";
  }
  function K(B) {
    y && x && (j += B);
    buildingShell.visible = y === "elevator" || y === "hvac" || y === "lighting";
    if (y === 'lighting') {
      if (!m && lightingViewport !== v.clientWidth + ':' + v.clientHeight) lightingContext();
      for (const { group, asset } of lightingFloors) group.visible = (N === '\u5168\u90E8' || N === asset.floor) && (!m || m === asset.id);
    }
    updateFloorCallouts();
    if (y === "hvac") for (const item of hvacLabels) {
      const projected = item.anchor.clone().project(e);
      const visible = (!m || m === item.id) && Math.abs(projected.x) < .94 && Math.abs(projected.y) < .88 && projected.z > -1 && projected.z < 1;
      item.label.visible = visible; item.leader.visible = visible;
      if (!visible) continue;
      // Display-sized labels identify registered units; leader endpoints stay on their source coordinates.
      const unitPerPixel = 2 * item.anchor.distanceTo(e.position) * Math.tan(e.fov * Math.PI / 360) / Math.max(1, v.clientHeight);
      item.label.position.copy(item.anchor).add(new ht(0, unitPerPixel * 34, 0).applyQuaternion(e.quaternion));
      item.label.scale.set(unitPerPixel * 128, unitPerPixel * 32, 1);
      const line = item.leader.geometry.getAttribute("position");
      line.setXYZ(0, item.anchor.x, item.anchor.y, item.anchor.z);
      line.setXYZ(1, item.label.position.x, item.label.position.y, item.label.position.z);
      line.needsUpdate = true; item.leader.geometry.computeBoundingSphere();
    }
    if (y === "elevator" && !m && elevatorViewport !== v.clientWidth + ':' + v.clientHeight) buildingContext();
    const et = y ? 1 : 0;
    M && (L = et), L = et > L ? Math.min(et, L + B / 0.8) : Math.max(et, L - B / 0.8), s.forEach(({ mesh: nt, m: I, opacity: U, transparent: J, depthWrite: g }) => {
      nt.visible = L < 0.999, I.opacity = U * (1 - L);
      const tt = L > 1e-3 || J;
      I.transparent !== tt && (I.transparent = tt, I.needsUpdate = true), I.depthWrite = L > 1e-3 ? false : g;
    }), a.visible = L > 1e-3, b.visible = L > 1e-3, T.forEach(({ material: nt, opacity: I }) => {
      nt.opacity = I * L * (y === "hvac" ? .22 : y === "lighting" ? .55 : 1) * (y === "elevator" && slabContextMaterials.has(nt) ? 0.22 : 1) * (m != null && m.startsWith("EL-") ? 0.65 : 1);
    }), !y && L === 0 && (P.forEach((nt) => nt.visible = true), t.background = u), D.children.forEach((nt) => {
      nt.visible = !m || nt.userData.assetId === m, nt.children.forEach((I) => {
        I.userData.floorLabel && (I.visible = y === "elevator");
        if (I.isSprite && y === "elevator") {
          I.renderOrder = 30;
          I.material.fog = false;
          const point = I.getWorldPosition(new ht());
          const pixelWorld = 2 * point.distanceTo(e.position) * Math.tan(e.fov * Math.PI / 360) / Math.max(1, v.clientHeight);
          const labelWidth = pixelWorld * (I.userData.floorLabel ? 48 : 100);
          I.scale.set(labelWidth, labelWidth / (I.userData.floorLabel ? 2 : 4), 1);
          if (I.userData.floorLabel) {
            I.position.copy(new ht(-pixelWorld * 38, 0, 0).applyQuaternion(e.quaternion));
            I.position.y += I.userData.floorY + .3;
          }
        }
      });
    }), i.forEach((nt, I) => {
      const U = W.find((g) => g.id === I);
      if (nt.visible = (N === "\u5168\u90E8" || U.floor === N) && (!m || m === I || y === "hvac"), U.system !== y) return;
      const J = _(I);
      nt.traverse((g) => {
        if (g.isMesh) {
          const tt = g.material;
          tt.color.copy(J === "\u79BB\u7EBF\u6837\u672C" ? new Nf("#8696a2") : tt.userData.baseColor), tt.emissive.set(J === "\u5F02\u5E38\u6837\u672C" ? "#c54820" : J === "\u79BB\u7EBF\u6837\u672C" ? "#000000" : U.system === "lighting" ? "#a96c0a" : m === I ? "#146774" : "#000000"), tt.emissiveIntensity = J === "\u5F02\u5E38\u6837\u672C" ? 0.35 + 0.2 * Math.sin(j * 2) : U.system === "lighting" ? J === "\u79BB\u7EBF\u6837\u672C" ? 0 : 0.8 : m === I ? 0.3 : 0;
        }
      });
    }), Z1.forEach((nt, I) => {
      const U = oI(j, I, nt.floors), J = l[I];
      J.car.position.y = U.y, J.doors[0].position.x = -0.45 - U.open * 0.72, J.doors[1].position.x = 0.45 + U.open * 0.72, J.marker.material.emissive.set(m === nt.id ? "#32bdda" : "#000000"), J.marker.material.emissiveIntensity = 0.6;
    });
  }
  function _(B) {
    var nt;
    const et = m || ((nt = W.find((I) => I.system === y)) == null ? void 0 : nt.id);
    return B === et && O === "alert" ? "\u5F02\u5E38\u6837\u672C" : B === et && O === "offline" ? "\u79BB\u7EBF\u6837\u672C" : y === "lighting" ? "\u4EAE\u706F\u6837\u672C" : y === "fire" ? "\u6B63\u5E38\u6837\u672C" : "\u8FD0\u884C\u6837\u672C";
  }
  const vt = new lRt();
  let rt = { x: 0, y: 0 };
  const A = (B) => {
    rt = { x: B.clientX, y: B.clientY };
  }, Q = (B) => {
    if (!y || Math.hypot(B.clientX - rt.x, B.clientY - rt.y) > 5) return;
    const et = v.getBoundingClientRect();
    vt.setFromCamera(new Me((B.clientX - et.left) / et.width * 2 - 1, -(B.clientY - et.top) / et.height * 2 + 1), e);
    const nt = c.filter((U) => {
      let J = U;
      for (; J; ) {
        if (!J.visible) return false;
        J = J.parent;
      }
      return true;
    }), I = vt.intersectObjects(nt, false)[0];
    I && R(I.object.userData.assetId);
  };
  return v.addEventListener("pointerdown", A), v.addEventListener("pointerup", Q), { enter: k, focus: R, update: K, assets: W, setHvacData, setScenario(B) {
    O = B;
  }, setFloor(B) {
    N = y === "hvac" ? "F3" : B, m = null, V.visible = false, G();
  }, setReturn(B) {
    H = B;
  }, setPlaying(B) {
    x = B;
  }, snapshot() {
    return { topic: y, selected: m, selectionRevision: X, clock: j, playing: x, blend: L, scenario: O, floorFilter: N, transparency: { style: "source-slab-envelope", filledMeshes: a.children.filter((B) => B.isMesh).length, materials: T.map(({ material: B }) => ({ opacity: B.opacity, depthWrite: B.depthWrite })) }, assetStates: W.filter((B) => B.system === y).map((B) => ({ id: B.id, state: _(B.id) })), assets: W.filter((B) => B.system === y), visuals: l.map((B, et) => {
      const nt = B.car.localToWorld(new ht(0, 1.1, 0)).project(e);
      return { id: Z1[et].id, y: B.car.position.y, doorLeft: B.doors[0].position.x, doorRight: B.doors[1].position.x, screenX: (nt.x + 1) * v.clientWidth / 2, screenY: (1 - nt.y) * v.clientHeight / 2 };
    }), lifts: Z1.map((B, et) => ({ id: B.id, ...oI(j, et, B.floors) })) };
  }, hotspots() {
    return nN.map((B, et) => {
      const nt = [[0, 23, -8], [-25, 23, 5], [-36, 9, 20], [25, 12, 0]], I = new ht(...nt[et]).project(e);
      return { ...B, x: (I.x + 1) / 2 * v.clientWidth, y: (1 - I.y) / 2 * v.clientHeight, visible: I.z >= -1 && I.z <= 1 };
    });
  }, dispose() {
    floorCallouts.remove();
    v.removeEventListener("pointerdown", A), v.removeEventListener("pointerup", Q);
  } };
}
const DSt = "";
const RE = { E4_Connection: [0.23999999463558197, 0.3499999940395355, 0.4000000059604645], E4_Temporary: [0.5699999928474426, 0.5, 0.4000000059604645], F3_CD_Gravity_Drain_Green: [0.10000000149011612, 0.38999998569488525, 0.27000001072883606], F3_Duct_Galvanized: [0.4699999988079071, 0.550000011920929, 0.5799999833106995], F3_Duct_Terminal_Grille: [0.2199999988079071, 0.28999999165534973, 0.3100000023841858], F3_IDU_Dark_Grille: [0.06499999761581421, 0.09000000357627869, 0.10499999672174454], F3_IDU_Metal_Body: [0.38999998569488525, 0.44999998807907104, 0.4699999988079071], F3_IDU_White_Panel: [0.8199999928474426, 0.8399999737739563, 0.8199999928474426], F3_RF_Extension_Insulated: [0.10999999940395355, 0.30000001192092896, 0.3400000035762787], F3_RF_Fitting_Slate: [0.14000000059604645, 0.33000001311302185, 0.36000001430511475], F3_RF_Insulated_Group_Slate: [0.10999999940395355, 0.30000001192092896, 0.3400000035762787], F3_RF_Partition_Sleeve_Approx: [0.550000011920929, 0.5099999904632568, 0.41999998688697815], F3_RF_Provisional_Label_Amber: [0.8199999928474426, 0.4300000071525574, 0.10000000149011612], F3_Service_Hollow_Sleeve: [0.5199999809265137, 0.44999998807907104, 0.30000001192092896], F3_Service_Support: [0.2199999988079071, 0.23000000417232513, 0.23999999463558197] };
const Fb = [{ id: "front", name: "\u4E3B\u7ACB\u9762\u5168\u666F", position: [126, 53, 157], target: [0, 10, 0], span: 40 }, { id: "terrace", name: "\u9732\u53F0\u4FA7\u5168\u666F", position: [-138, 66, 153], target: [0, 10, 0], span: 41 }, { id: "rear", name: "\u80CC\u7ACB\u9762\u5168\u666F", position: [-130, 58, -165], target: [0, 10, 0], span: 41 }, { id: "aerial", name: "\u9AD8\u4F4D\u9E1F\u77B0", position: [90, 160, 180], target: [0, 5, 0], span: 46 }];
const qSt = `
 varying vec3 vSurfacePosition;
 float surfaceHash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
`;
function FSt(t) {
  var r;
  const e = t.toLowerCase(), f = { color: "#bab7ae", roughness: 0.72, metalness: 0.03 };
  RE[t] ? Object.assign(f, { color: new Nf().fromArray(RE[t]), roughness: 0.65, metalness: 0.2 }) : /e5_fan|hvac_grille/.test(e) ? Object.assign(f, { color: "#343b3d", roughness: 0.62, metalness: 0.45 }) : /e5_body|hvac_casing/.test(e) ? Object.assign(f, { color: "#b7bcb7", roughness: 0.48, metalness: 0.3 }) : /e5_foot|e1_metal|e1_handle|e2_support|tray_galvanized/.test(e) ? Object.assign(f, { color: "#8d979a", roughness: 0.48, metalness: 0.65 }) : /refrigerant|conduit/.test(e) ? Object.assign(f, { color: "#727b77", roughness: 0.68, metalness: 0.25 }) : /e1_fire|tray_fire/.test(e) ? Object.assign(f, { color: "#963f31", roughness: 0.65, metalness: 0.2 }) : /e1_blue/.test(e) ? Object.assign(f, { color: "#456477", roughness: 0.6, metalness: 0.2 }) : /window_room/.test(e) ? Object.assign(f, { color: ["#48616d", "#687c82", "#526972", "#80918e", "#3f5c6b"][Number(((r = e.match(/room_(\d)/)) == null ? void 0 : r[1]) || 0) % 5], roughness: 0.22, metalness: 0.38, envMapIntensity: 1.4 }) : /plant_green/.test(e) ? Object.assign(f, { color: "#456032", roughness: 0.95 }) : /interior_linear_light/.test(e) ? Object.assign(f, { color: "#bcc3b4", emissive: "#78775a", emissiveIntensity: 0.15 }) : /glass/.test(e) ? Object.assign(f, { color: "#668c9b", roughness: 0.115, metalness: 0.48, envMapIntensity: 1.65 }) : /orange/.test(e) ? Object.assign(f, { color: "#b74c20", roughness: 0.38, metalness: 0.25 }) : /charcoal|black|anodized|seal/.test(e) ? Object.assign(f, { color: "#303e46", roughness: 0.38, metalness: 0.5 }) : /white.*aluminium/.test(e) ? Object.assign(f, { color: "#c5c1b6", roughness: 0.43, metalness: 0.35 }) : /roof.*membrane/.test(e) ? Object.assign(f, { color: "#77766e", roughness: 0.88, metalness: 0 }) : /stone|limestone/.test(e) ? Object.assign(f, { color: "#b0a794", roughness: 0.82, metalness: 0 }) : /plaster/.test(e) ? Object.assign(f, { color: "#c3bcae", roughness: 0.9, metalness: 0 }) : /stainless/.test(e) && Object.assign(f, { color: "#a7b3b5", roughness: 0.28, metalness: 0.75 });
  const n = /glass|window_room/.test(e), v = n ? new zRt({ ...f, clearcoat: 1, clearcoatRoughness: 0.08, ior: 1.52 }) : new x7(f);
  return v.onBeforeCompile = (z) => {
    z.vertexShader = `varying vec3 vSurfacePosition;
` + z.vertexShader, z.vertexShader = z.vertexShader.replace("#include <begin_vertex>", `#include <begin_vertex>
vSurfacePosition=position;`), z.fragmentShader = qSt + z.fragmentShader;
    const u = n ? `vec2 pane=floor(vec2(vSurfacePosition.x+vSurfacePosition.z,vSurfacePosition.y)*vec2(.65,.65));
   float variation=surfaceHash(pane);diffuseColor.rgb*=mix(.78,1.17,variation);
   diffuseColor.rgb+=vec3(.025,.033,.031)*smoothstep(.82,.98,variation);` : /stone|plaster|roof|concrete/.test(e) ? `float grain=surfaceHash(floor(vSurfacePosition.xz*110.+vSurfacePosition.yy*71.));diffuseColor.rgb*=mix(.91,1.035,grain);
   float weather=sin(vSurfacePosition.x*.7+vSurfacePosition.z*.39)*sin(vSurfacePosition.y*.47);diffuseColor.rgb*=1.+weather*.035;` : "";
    z.fragmentShader = z.fragmentShader.replace("#include <color_fragment>", `#include <color_fragment>
` + u);
  }, v.customProgramCacheKey = () => n ? "glazing-v2" : /stone|plaster|roof|concrete/.test(e) ? "mineral-v2" : "solid-v2", v;
}
async function RSt(t, e, signal) {
  signal?.throwIfAborted();
  const buildingInput = preparedModel(new URL("./building-scene.bin.gz", import.meta.url));
  // 提前发起资源读取，与本场景的WebGL初始化并行；拒绝仍由后续await及原有释放路径处理。
  void buildingInput.catch(() => {});
  const f = new mSt({ antialias: true, alpha: false, powerPreference: "high-performance" });
  // Physical render pixels follow DPR; display pixels must remain tied to the viewport.
  Object.assign(f.domElement.style, { display: "block", width: "100%", height: "100%" });
  f.setPixelRatio(Math.min(devicePixelRatio, 1.35)), f.outputColorSpace = Qv, f.toneMapping = RI, f.toneMappingExposure = 1, f.shadowMap.enabled = true, f.shadowMap.autoUpdate = false, f.shadowMap.needsUpdate = true, f.shadowMap.type = VI, t.append(f.domElement), f.domElement.setAttribute("aria-label", "\u521B\u65B0\u6E2F\u6574\u680B\u4E09\u7EF4\u573A\u666F"), f.domElement.tabIndex = 0;
  const n = new yFt();
  n.fog = new OM("#c5d2d8", 260, 1050);
  const v = document.createElement("canvas");
  v.width = 2048, v.height = 1024;
  const r = v.getContext("2d"), z = r.createLinearGradient(0, 0, 0, 1024);
  z.addColorStop(0, "#3679b5"), z.addColorStop(0.26, "#75b2dc"), z.addColorStop(0.48, "#c5d2d8"), z.addColorStop(0.51, "#c5d2d8"), z.addColorStop(0.58, "#c5d2d8"), z.addColorStop(1, "#c5d2d8"), r.fillStyle = z, r.fillRect(0, 0, 2048, 1024);
  for (let G = 0; G < 90; G++) {
    const R = G * 197 % 2048, k = 170 + G * 43 % 250, K = r.createRadialGradient(R, k, 0, R, k, 100);
    K.addColorStop(0, "rgba(255,250,239,.22)"), K.addColorStop(0.5, "rgba(249,247,242,.09)"), K.addColorStop(1, "rgba(249,247,242,0)"), r.save(), r.translate(0, k), r.scale(1, 0.32), r.translate(0, -k), r.fillStyle = K, r.fillRect(R - 100, k - 100, 200, 200), r.restore();
  }
  const u = new TM(v);
  u.colorSpace = Qv, u.mapping = $W, n.background = u;
  const b = new ht(-0.85, 0.88, 0.62).normalize(), a = new EM(f);
  let P = a.fromEquirectangular(u);
  n.environment = P.texture, n.environmentIntensity = 0.65, n.add(new aRt("#d7e5f3", "#746751", 0.65));
  const s = new dRt("#fff1da", 3.5);
  s.position.copy(b).multiplyScalar(220), s.castShadow = true, s.shadow.mapSize.set(4096, 4096), Object.assign(s.shadow.camera, { left: -130, right: 130, top: 110, bottom: -110, near: 1, far: 500 }), s.shadow.normalBias = 0.025, s.shadow.bias = -12e-5, n.add(s);
  const d = new qr(43, 1, 0.5, 2e4), c = new LSt(d, f.domElement);
  c.enableDamping = false, c.enablePan = false, c.minDistance = 130, c.maxDistance = 400, c.maxPolarAngle = Math.PI * 0.47;
  const l = { ready: false, active: false, frames: 0, triangles: 0, drawCalls: 0, view: "front", geometryCount: 0, textureCount: 0, gpu: "", contextLosses: 0, renderMs: [] }, W = f.getContext(), X = W.getExtension("WEBGL_debug_renderer_info");
  l.gpu = String(X ? W.getParameter(X.UNMASKED_RENDERER_WEBGL) : W.getParameter(W.RENDERER));
  let y = false, m = true, j = true, x = 0, L = 0, O = 0, N = Fb[0].span, i, H;
  const M = /* @__PURE__ */ new Map();
  let p, T = false, preloadFrame = 0, preloadTimer = 0, hvacPending = null, hvacReady = false;
  const w = (G, R, k) => {
    c.enabled = false, c.minDistance = 5, i = { start: performance.now(), pos: d.position.clone(), target: c.target.clone(), span: N, preset: { position: G, target: R, span: k } }, matchMedia("(prefers-reduced-motion: reduce)").matches && (d.position.fromArray(G), c.target.fromArray(R), N = k, i = void 0, c.update(), Y());
  }, Y = () => {
    const G = t.clientWidth, R = t.clientHeight;
    !G || !R || y || (d.aspect = G / R, d.fov = N, d.updateProjectionMatrix(), f.setSize(G, R, false));
  }, q = new ResizeObserver(Y);
  q.observe(t);
  const D = (G) => {
    G.preventDefault(), l.contextLosses++, m = false, cancelAnimationFrame(x), e();
  };
  f.domElement.addEventListener("webglcontextlost", D);
  function V() {
    if (y) return;
    y = true, cancelAnimationFrame(preloadFrame), clearTimeout(preloadTimer), H == null || H.dispose(), m = false, l.active = false, cancelAnimationFrame(x), q.disconnect(), c.dispose(), f.domElement.removeEventListener("webglcontextlost", D);
    const G = /* @__PURE__ */ new Set(), R = /* @__PURE__ */ new Set(), k = /* @__PURE__ */ new Set();
    n.traverse((K) => {
      const _ = K;
      _.geometry && G.add(_.geometry);
      for (const vt of Array.isArray(_.material) ? _.material : _.material ? [_.material] : []) {
        R.add(vt);
        for (const rt of Object.values(vt)) rt instanceof Tv && k.add(rt);
      }
    }), G.forEach((K) => K.dispose()), R.forEach((K) => K.dispose()), k.forEach((K) => K.dispose()), u.dispose(), P.dispose(), a.dispose(), s.shadow.dispose(), f.renderLists.dispose(), f.dispose(), f.forceContextLoss(), f.domElement.remove();
  }
  try {
    // 环境反射原本就排除建筑网格；保持相同景观与光照，在模型下载期间完成CPU及GPU准备。
    const A = jSt(n);
    A(0), f.shadowMap.needsUpdate = true;
    const Q = new NK(256, { type: Ba }), B = new OK(0.5, 1800, Q);
    B.position.set(0, 18, 0), B.update(f, n);
    const et = a.fromCubemap(Q.texture);
    P.dispose(), P = et, n.environment = P.texture, Q.dispose(), f.shadowMap.needsUpdate = true;
    const data = await buildingInput, rt = [];
    signal?.throwIfAborted();
    function meshGeometry(entry) {
      const geometry = new bv();
      geometry.setAttribute("position", new ov(entry.positions, 3));
      geometry.setAttribute("normal", new ov(entry.normals, 3, true));
      if (entry.index) geometry.setIndex(new ov(entry.index, 1));
      geometry.computeBoundingSphere();
      return geometry;
    }
    for (const entry of data.meshes) {
      const geometry = meshGeometry(entry), mesh = new Zn(geometry, FSt(entry.material));
      mesh.name = entry.material; mesh.castShadow = !/glass/.test(entry.material.toLowerCase()); mesh.receiveShadow = true;
      n.add(mesh); rt.push(mesh); M.set(entry.material, geometry);
    }
    function prepareSystem(topic) {
      if (topic !== "hvac" || hvacReady || y) return Promise.resolve();
      if (hvacPending) return hvacPending;
      hvacPending = preparedModel(new URL("./f3-hvac.bin.gz", import.meta.url)).then(model => {
        if (y) return;
        const prepared = { ...model, meshes: model.meshes.map(entry => ({ ...entry, geometry: meshGeometry(entry) })) };
        H.setHvacData(prepared); hvacReady = true; f.shadowMap.needsUpdate = true;
      }).catch(error => { hvacPending = null; throw error; });
      return hvacPending;
    }
    // 外观先绘制；专业资源至多一份预取，隐藏/释放时取消尚未开始的工作，失败由实际进入时重试。
    function queuePreload() {
      if (y || !m || document.hidden || hvacReady || hvacPending || preloadFrame || preloadTimer) return;
      preloadFrame = requestAnimationFrame(() => {
        preloadFrame = requestAnimationFrame(() => {
          preloadFrame = 0;
          preloadTimer = setTimeout(() => {
            preloadTimer = 0;
            if (!y && m && !document.hidden) void prepareSystem("hvac").catch(() => {});
          }, 200);
        });
      });
    }
    H = YSt(n, d, rt, M, t, w, () => {
      return p || { position: d.position.toArray(), target: c.target.toArray(), span: N };
    }, null), H.setReturn(() => {
      p && w(p.position, p.target, p.span);
    });
    const nt = (U, J = false) => {
      const g = Fb.find((tt) => tt.id === U) || Fb[0];
      l.view = g.id, d.zoom = 1, J || matchMedia("(prefers-reduced-motion: reduce)").matches ? (d.position.fromArray([...g.position]), c.target.fromArray([...g.target]), N = g.span, i = void 0, c.update(), Y()) : i = { start: performance.now(), pos: d.position.clone(), target: c.target.clone(), span: N, preset: g };
    }, I = (U) => {
      if (y || !m) {
        x = 0;
        return;
      }
      if (x = requestAnimationFrame(I), U - L < 33) return;
      const J = Math.min((U - L) / 1e3, 0.1);
      if (L = U, j && (O += J), i) {
        const tt = Math.min(1, (U - i.start) / (H != null && H.snapshot().topic ? 1400 : 850)), zt = 1 - (1 - tt) ** 3;
        d.position.lerpVectors(i.pos, new ht(...i.preset.position), zt), c.target.lerpVectors(i.target, new ht(...i.preset.target), zt), N = a6.lerp(i.span, i.preset.span, zt), c.update(), Y(), tt === 1 && (i = void 0, H != null && H.snapshot().topic || (c.minDistance = 130));
      }
      H == null || H.update(J), p && !(H != null && H.snapshot().topic) && !i && (H == null ? void 0 : H.snapshot().blend) === 0 && (p = void 0, c.enabled = T, c.minDistance = 130), H != null && H.snapshot().topic || A(O);
      const g = performance.now();
      f.render(n, d), l.renderMs.push(performance.now() - g), l.renderMs.length > 90 && l.renderMs.shift(), l.frames++, l.triangles = f.info.render.triangles, l.drawCalls = f.info.render.calls, l.geometryCount = f.info.memory.geometries, l.textureCount = f.info.memory.textures;
    };
    if (nt("front", true), A(0), Y(), W.isContextLost()) throw Error("WebGL context lost");
    return l.ready = true, { stats: l, preset: nt, dispose: V, prepareSystem, viewState() {
      return { position: d.position.toArray(), target: c.target.toArray(), transition: !!i, opaque: rt.every((U) => U.material.opacity === 1) };
    }, enterSystem(U) {
      U && !p && (p = { position: d.position.toArray(), target: c.target.toArray(), span: N }, T = c.enabled), H == null || H.enter(U);
    }, setSystemFloor(U) {
      H == null || H.setFloor(U);
    }, setScenario(U) {
      H == null || H.setScenario(U);
    }, selectAsset(U) {
      H == null || H.focus(U);
    }, systemSnapshot() {
      return H.snapshot();
    }, hotspots() {
      return H.hotspots();
    }, setDemoPlaying(U) {
      H == null || H.setPlaying(U);
    }, inspectView(U, J, g = 40) {
      i = void 0, c.enabled = false, d.position.fromArray(U), c.target.fromArray(J), N = g, d.zoom = 1, c.update(), Y();
    }, setMotion(U) {
      j = U;
    }, setActive(U) {
      m = U && !document.hidden, l.active = m, cancelAnimationFrame(x), x = 0;
      if (m) { L = performance.now(); x = requestAnimationFrame(I); queuePreload(); }
      else { cancelAnimationFrame(preloadFrame); clearTimeout(preloadTimer); preloadFrame = 0; preloadTimer = 0; }
    }, setFree(U) {
      c.enabled = U;
    }, reset() {
      nt(l.view);
    }, getCanvas() {
      return f.domElement;
    } };
  } catch (G) {
    throw V(), G;
  }
}
export {
  RSt as createReferenceScene,
  Fb as referenceViews
};
