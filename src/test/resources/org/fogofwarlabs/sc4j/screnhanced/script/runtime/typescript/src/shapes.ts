export abstract class Shape {
    constructor(public readonly name: string) {
    }

    abstract area(): number;

    describe(): string {
        return `${this.name} ${this.area()}`;
    }
}

export class Rectangle extends Shape {
    constructor(private readonly width: number, private readonly height: number) {
        super('rectangle');
    }

    area(): number {
        return this.width * this.height;
    }
}

export class Square extends Rectangle {
    static count = 0;

    constructor(side: number) {
        super(side, side);
        Square.count++;
    }

    describe(): string {
        return 'square ' + super.describe();
    }
}
