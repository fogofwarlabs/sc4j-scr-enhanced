"use strict";
var __assign = (this && this.__assign) || function () {
    __assign = Object.assign || function(t) {
        for (var s, i = 1, n = arguments.length; i < n; i++) {
            s = arguments[i];
            for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p))
                t[p] = s[p];
        }
        return t;
    };
    return __assign.apply(this, arguments);
};
var __spreadArray = (this && this.__spreadArray) || function (to, from, pack) {
    if (pack || arguments.length === 2) for (var i = 0, l = from.length, ar; i < l; i++) {
        if (ar || !(i in from)) {
            if (!ar) ar = Array.prototype.slice.call(from, 0, i);
            ar[i] = from[i];
        }
    }
    return to.concat(ar || Array.prototype.slice.call(from));
};
var _a, _b, _c;
Object.defineProperty(exports, "__esModule", { value: true });
var shapes_1 = require("./shapes");
var util_1 = require("./util");
var util = require("./util");
var results = [];
function record(name, value) {
    results.push(name + '=' + value);
}
var shapes = [new shapes_1.Rectangle(2, 3), new shapes_1.Square(3)];
record('classes', shapes.map(function (shape) { return shape.describe(); }).join(','));
record('instanceof', (shapes[1] instanceof shapes_1.Rectangle) + '/' + shapes_1.Square.count);
var callbacks = [];
var _loop_1 = function (index) {
    var doubled = index * 2;
    callbacks.push(function () { return index + doubled; });
};
for (var index = 0; index < 3; index++) {
    _loop_1(index);
}
record('loops', callbacks.map(function (callback) { return callback(); }).join(','));
var _d = [1, 2, undefined, 4, 5], first = _d[0], _e = _d[2], third = _e === void 0 ? 9 : _e, rest = _d.slice(3);
var _f = { nested: { value: 7 } }, _g = _f.label, label = _g === void 0 ? 'none' : _g, _h = _f.nested, _j = _h === void 0 ? {} : _h, _k = _j.value, value = _k === void 0 ? 0 : _k;
var merged = __assign({ a: 1 }, { b: 2 });
record('destructuring', "".concat(first, " ").concat(third, " ").concat(rest.join('+'), " ").concat(label, " ").concat(value, " ").concat(merged.a).concat(merged.b));
record('spread', util_1.sum.apply(void 0, __spreadArray(__spreadArray([], [1, 2, 3], false), [4], false)) + ' ' + __spreadArray(__spreadArray([], rest, true), rest, true).length);
var options = Math.max(1, 2) > 1 ? {} : undefined;
record('optional', "".concat((_b = (_a = options === null || options === void 0 ? void 0 : options.nested) === null || _a === void 0 ? void 0 : _a.value) !== null && _b !== void 0 ? _b : 'missing', " ").concat((_c = options === null || options === void 0 ? void 0 : options.label) === null || _c === void 0 ? void 0 : _c.length));
record('enum', "".concat(util_1.Mode.Hard, " ").concat(util_1.Mode[util_1.Mode.Insane], " ").concat(1 /* Flag.A */ | 2 /* Flag.B */));
record('namespace', util_1.Scores.double(util_1.Scores.base) + ' ' + util.Scores.base);
var counted = [];
var iterator = (0, util_1.countTo)(3);
for (var step = iterator.next(); !step.done; step = iterator.next()) {
    counted.push(step.value);
}
for (var _i = 0, shapes_2 = shapes; _i < shapes_2.length; _i++) {
    var shape = shapes_2[_i];
    counted.push(shape.area());
}
record('generator', counted.join(','));
(0, util_1.delayedSum)(20, 22).then(function (total) { return log.info('async=' + total); });
log.info(results.join(' | '));
defineHooks({
    onStart: function () {
        log.info('started');
    },
    onFrame: function (frame) {
        if (frame === 1) {
            log.info('frame ' + frame);
        }
        if (frame === 2) {
            throw new Error('stopped at frame ' + frame);
        }
    }
});
//# sourceMappingURL=main.js.map