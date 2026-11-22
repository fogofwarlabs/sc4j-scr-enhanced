export enum Mode {
    Easy,
    Hard = 5,
    Insane
}

export const enum Flag {
    A = 1,
    B = 2
}

export namespace Scores {
    export const base = 10;

    export function double(value: number): number {
        return value * 2;
    }
}

export function* countTo(limit: number): IterableIterator<number> {
    for (let index = 1; index <= limit; index++) {
        yield index;
    }
}

export function sum(...values: number[]): number {
    let total = 0;

    for (const value of values) {
        total += value;
    }

    return total;
}

export async function delayedSum(first: number, second: number): Promise<number> {
    const left = await Promise.resolve(first);
    const right = await second;

    return left + right;
}
