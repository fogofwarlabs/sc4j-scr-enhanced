import { Rectangle, Shape, Square } from './shapes';
import { countTo, delayedSum, Flag, Mode, Scores, sum } from './util';
import * as util from './util';

interface Options {
    label?: string;
    nested?: { value?: number };
}

const results: string[] = [];

function record(name: string, value: unknown): void {
    results.push(name + '=' + value);
}

const shapes: Shape[] = [new Rectangle(2, 3), new Square(3)];

record('classes', shapes.map((shape) => shape.describe()).join(','));
record('instanceof', (shapes[1] instanceof Rectangle) + '/' + Square.count);

const callbacks: Array<() => number> = [];

for (let index = 0; index < 3; index++) {
    const doubled = index * 2;

    callbacks.push(() => index + doubled);
}

record('loops', callbacks.map((callback) => callback()).join(','));

const [first, , third = 9, ...rest] = [1, 2, undefined, 4, 5];
const { label = 'none', nested: { value = 0 } = {} }: Options = { nested: { value: 7 } };
const merged = { ...{ a: 1 }, b: 2 };

record('destructuring', `${first} ${third} ${rest.join('+')} ${label} ${value} ${merged.a}${merged.b}`);
record('spread', sum(...[1, 2, 3], 4) + ' ' + [...rest, ...rest].length);

const options: Options | undefined = Math.max(1, 2) > 1 ? {} : undefined;

record('optional', `${options?.nested?.value ?? 'missing'} ${options?.label?.length}`);
record('enum', `${Mode.Hard} ${Mode[Mode.Insane]} ${Flag.A | Flag.B}`);
record('namespace', Scores.double(Scores.base) + ' ' + util.Scores.base);

const counted: number[] = [];
const iterator = countTo(3);

for (let step = iterator.next(); !step.done; step = iterator.next()) {
    counted.push(step.value);
}

for (const shape of shapes) {
    counted.push(shape.area());
}

record('generator', counted.join(','));

delayedSum(20, 22).then((total) => log.info('async=' + total));

log.info(results.join(' | '));

defineHooks({
    onStart() {
        log.info('started');
    },

    onFrame(frame) {
        if (frame === 1) {
            log.info('frame ' + frame);
        }

        if (frame === 2) {
            throw new Error('stopped at frame ' + frame);
        }
    }
});
