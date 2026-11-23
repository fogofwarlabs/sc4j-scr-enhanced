# Enhanced Script

**English** | [한국어](script.ko.md)

How to write map scripts for SC:R Enhanced. The full API reference is the declaration files [`scr-enhanced.d.ts`](../src/mod/resources/org/fogofwarlabs/sc4j/screnhanced/script/scr-enhanced.d.ts) and [`core.d.ts`](../src/mod/resources/org/fogofwarlabs/sc4j/screnhanced/script/core.d.ts); `init` copies both into every new script folder.

- [1. What it is](#1-what-it-is)
- [2. Getting started](#2-getting-started)
- [3. Shared code and local code](#3-shared-code-and-local-code)
- [4. The API at a glance](#4-the-api-at-a-glance)
- [5. Examples](#5-examples)
- [6. Map protection](#6-map-protection)
- [7. Limits](#7-limits)

## 1. What it is

A map script is JavaScript stored inside the map file. SC:R Enhanced runs it with [Rhino](https://github.com/mozilla/rhino) 1.8.1 in interpreted mode, at the newest ECMAScript level Rhino supports; chapter 7 lists the limits.

- The map file keeps its own data as it is; the script package (the manifest, the scripts and the files of `resources/`) is stored after it.
- The script runs on every player's game in lockstep: the same code, at the same frame, with the same results. Every player of a game runs SC:R Enhanced.
- A replay holds the scripts and plays them again. It reads the resources from the map: keep the map in `<install>/userdata/mods/scr-enhanced/maps/`.

## 2. Getting started

### 2.1 The script tool

The script tool is the tool `script` of the mod `scr-enhanced`. Run it with the SC4J install's `SC4J.bat` (Linux `SC4J`, Windows console `SC4J-cli.exe`):

```powershell
SC4J.bat tool scr-enhanced script <command> [arguments]
```

Relative paths are relative to the current folder.

| Command | What it does |
|---|---|
| `init <folder>` | writes a new script folder; the folder must be absent or empty |
| `check <folder>` | prints the errors and warnings of a script folder |
| `run <map> --script <folder> [--melee] [--watch] [--inspect[=<port>]] [--install <folder>]` | packs the folder into a copy of the map and starts SC:R Enhanced on it |
| `pack <map> --script <folder> -o <output>` | writes the map with the script package to `<output>`; an old package is replaced |
| `pack <map> --script <folder> -o <output> --protect` | the same, as a protected map (chapter 6) |
| `unpack <map> -o <folder>` | writes the script package of a map to a folder that is absent or empty |
| `trace <source map> --at <script>:<line>` | prints the original line of a line of a protected script |
| `trace <source map> --log <file>` | prints a file, such as a log, with the original lines |

`run` starts a Use Map Settings game, or with `--melee` a melee game against one computer of a random race. The copy of the map goes to `<install>/userdata/mods/scr-enhanced/script-run/`; the source map stays as it is. With `--watch` a change of the folder is packed again and loads when the map starts again. With `--inspect` DevTools can debug the script (section 2.7). `--install` names another SC4J install. The command ends when the game ends.

In a clone of this repository the same commands run with Gradle, paths relative to the repository root:

```powershell
.\gradlew.bat runScriptTool --args="init my-map"
```

### 2.2 A script folder

```powershell
SC4J.bat tool scr-enhanced script init my-map
```

| File | Content |
|---|---|
| `manifest.json` | `{"name": "my-map", "main": "main.js", "api": "1"}`: the package name (the folder name), the script that runs first, the script API version |
| `main.js` | the main script, an example with `onStart` and `onFrame` |
| `jsconfig.json` | editor settings: only the declaration files, scripts checked, every file its own module |
| `scr-enhanced.d.ts` | declarations of the upper API, the runtime and the standard objects |
| `core.d.ts` | declarations of the lower API `core` |
| `resources/` | files the script reads: GRP, anim, PNG and DDS, WAV and Ogg sounds, and any file as text or JSON |

Only `manifest.json`, the `.js` files outside `resources/` and the files under `resources/` go into the map.

### 2.3 Editor completion

Open the folder in an editor with TypeScript-based JavaScript support, such as Visual Studio Code. `jsconfig.json` makes it complete and check the scripts against `scr-enhanced.d.ts` and `core.d.ts`, without browser or Node.js globals.

Give the parameter of a top-level function hook a type with JSDoc. The methods of `defineHooks` (section 3.1) get their types from the declarations without it.

```js
/** @param {UnitDiedEvent} event */
function onUnitDied(event) {
    log.info(event.getUnitType().getName());
}
```

### 2.4 Modules

Each `.js` file is a module with its own top level. `require` loads another script once and returns its `module.exports`; `./` and `../` are relative to the folder of the requiring script, other ids relative to the script folder, and `.js` may be left out. Add properties to `exports`, or assign `module.exports` to export another value.

```js
// bonus.js
exports.give = function (player, amount) {
    player.addMinerals(amount);
};
```

```js
// main.js
const bonus = require('./bonus');

function onStart() {
    game.players.getHumans().forEach((player) => bonus.give(player, 100));
}
```

Every module can define hooks (section 3.1). Call `require` in shared code (chapter 3): local code cannot load a new module.

### 2.5 Logs

`log.info`, `log.warn` and `log.error` write to the SC:R Enhanced log `<install>/userdata/logs/scr-enhanced/sc4j.log`, prefixed with the module name. Script errors go there too, with the script file and line that threw them (`main.js:12`); for an error of an API call, the line of the call.

### 2.6 TypeScript

Scripts can be written in TypeScript and compiled with `tsc` (checked with TypeScript 5.9). The map holds only the compiled JavaScript.

```text
my-map/                 the TypeScript project
  tsconfig.json
  src/main.ts           the sources
  src/bonus.ts
  script/               the script folder that is packed: made by init, filled by tsc
    manifest.json       {"name": "my-map", "main": "main.js", "api": "1"}
    scr-enhanced.d.ts
    core.d.ts
    resources/
```

Make `script/` with `init my-map/script`, set the name in its `manifest.json`, delete its `main.js` and `jsconfig.json`, and add `my-map/tsconfig.json`:

```json
{
    "compilerOptions": {
        "target": "ES5",
        "module": "commonjs",
        "moduleDetection": "force",
        "noLib": true,
        "types": [],
        "strict": true,
        "noImplicitReturns": true,
        "rootDir": "src",
        "outDir": "script"
    },
    "files": ["script/scr-enhanced.d.ts", "script/core.d.ts"],
    "include": ["src/**/*.ts"]
}
```

- `files` names the declaration files, because tsc leaves the files of `outDir` out of `include`.
- tsc writes `main.js` and the other modules next to `manifest.json` and copies no other file: `manifest.json` and `resources/` stay in `script/`.
- Define the hooks with `defineHooks` (section 3.1): tsc types their parameters and reports a misspelled hook name. tsc does not know that the runtime calls top-level function hooks: `noUnusedLocals` reports them, and a misspelled name goes unnoticed.
- Import modules with relative paths (`./bonus`).

```powershell
tsc -p my-map
SC4J.bat tool scr-enhanced script check my-map/script
SC4J.bat tool scr-enhanced script pack Map.scx --script my-map/script -o Out.scx
```

`tsc -w -p my-map` next to `run Map.scx --script my-map/script --watch` loads each compile when the map starts again. `--protect` works on the output too.

```ts
// main.ts
import { give } from './bonus';

defineHooks({
    onStart() {
        game.players.getHumans().forEach((player) => give(player, 100));
    }
});
```

```ts
// bonus.ts
export function give(player: Player, amount: number): void {
    player.addMinerals(amount);
}
```

What works with target ES5 (tsc writes its helpers into each file):

- classes with inheritance, `super`, getters and static members
- arrow functions, template strings, rest and default parameters
- `let` and `const`, also in loops with closures
- destructuring with defaults and rest elements
- spread in calls, arrays and objects
- optional chaining `?.` and `??`
- enums, const enums and namespaces
- generators
- `async` and `await` on the runtime's `Promise`; the jobs run as chapter 7 says
- `for...of` over arrays; over `Map`, `Set` and strings with `"downlevelIteration": true`
- imports between modules, default imports included

What does not:

- Targets ES2015 and later keep forms Rhino lacks: `class`, spread, rest elements in destructuring and `for (const ...)` from ES2015, `async` functions from ES2017, object spread from ES2018. `let` and `const` in loops then follow Rhino's loop rules (chapter 7). `check` reports these.
- `noEmitHelpers` leaves the helpers out, and the scripts fail with a `ReferenceError`. `importHelpers` loads them from a module `tslib`: copy `tslib.js` of the npm package `tslib` into the script folder.
- `module` other than `commonjs`, `import()`, and the libraries of browsers and Node.js.
- Errors and logs name the line of the compiled JavaScript (`main.js:72`), not of the TypeScript source.

### 2.7 DevTools

Chrome or Edge DevTools can debug the script of a running game: a console with completion, object previews and the script's log, the scripts in the Sources panel, breakpoints and stepping.

DevTools is off unless `run` starts the game with `--inspect`:

```powershell
SC4J.bat tool scr-enhanced script run Map.scx --script my-map --inspect
```

| Start | Port |
|---|---|
| `run ... --inspect` | 9339 |
| `run ... --inspect=9340` | 9340 |

When it attaches to a game, the message area shows `DevTools console: 127.0.0.1:9339, shared code`.

Open `chrome://inspect` (Edge: `edge://inspect`), choose Configure, add `127.0.0.1:9339`, and click inspect under the target `SC:R Enhanced script`. The log `<install>/userdata/logs/scr-enhanced/sc4j.log` also has the address of the DevTools window (`devtools://devtools/bundled/js_app.html?...`); paste it into the address bar to open it directly.

Console code runs between frames, at the frame start after `onFrame`. Its context decides what it may do (chapter 3); pick the context in the context menu at the top of the Console panel.

| Game | Contexts |
|---|---|
| single player, also `run` | `Shared code` (the default): may change the game and the script's variables; `Local code` |
| multiplayer, replay | `Local code` only: the shared-only functions throw, so the console cannot make the games differ |

What the console has:

- The main script's top-level names, `require`, `log` and the globals. Names that the console declares stay in the console.
- `$_`: the last result.
- `modules`: the top level of each loaded module by file name, such as `modules['bonus.js'].count`.
- Assigning a top-level name of the script, such as `score = 10`, changes it in shared code and throws a `TypeError` in local code.
- Objects expand. A unit, player, type or position shows its name (`Unit 12`, `Player 0`) and the values of its `get`, `is` and `has` methods.
- `log.info`, `log.warn`, `log.error` and the script's errors appear in the console, an error with its file and line.
- A line has the instruction budget of one call (chapter 7). A line over the budget ends with an error; the script runs on.

The debugger:

- The Sources panel lists the scripts of the map by file name, read only. With TypeScript it shows the compiled `.js` files.
- Breakpoints on a line, also with a condition; `debugger` statements; Pause on exceptions, which also pauses at an error that the script catches.
- At a pause: the call stack, the variables of each frame and the functions around it, the module and the globals; Step over, into and out, Resume; console code runs in the selected frame.
- A pause holds the game thread: the game and its window stop until Resume, and in a multiplayer game every player waits or drops.

What it does not have:

- No column breakpoints, no Restart frame, no editing of the scripts, no profiler.
- Code waits while the game runs no frame: while the game menu pauses a single-player game, or while a replay is paused. After 5 seconds DevTools shows an error.
- While you type, DevTools previews names and property reads only; it calls no function.
- In local code, changing an object of the script, such as `state.score = 1`, or calling a function that does is not refused. As in the local hooks, do not change what shared code reads (section 3.3).
- The replay of a game whose console changed the game does not have those changes.
- The server listens on 127.0.0.1 only and refuses web pages: a request with another `Host` or a web page's `Origin` header.

## 3. Shared code and local code

| | Shared code | Local code |
|---|---|---|
| Where | the module top level, `onStart`, `onFrame`, timers, event hooks, decision hooks, channel handlers, `onPressed` of a script button | `onLocalFrame`, `onRender`, the `onLocal*` input hooks, `onLocalPressed` of a script button |
| Runs | on every player's game and in every replay, the same way | on this player's machine only |
| May | change the game, add timers, create channels and buttons, draw random numbers, load modules | read the game, use `local`, draw, move the local screen, send over channels |
| May not | use `local` (throws a `TypeError`) | change the game, add or cancel timers, create channels, draw random numbers, load modules (each throws a `TypeError`) |

A member marked "Shared code only." or "Local code only." in the declarations throws a `TypeError` on the other side; "Only in onRender." throws outside `onRender`.

### 3.1 Hooks

A hook is a function with a known name: a method of an object given to `defineHooks`, or a top-level function.

```js
defineHooks({
    onStart() {
        game.print('Defend the base.');
    },

    onFrame(frame) {
        if (frame === Time.fromMinutes(10)) {
            game.print('You won.');
        }
    }
});
```

```js
function onStart() {
    game.print('Defend the base.');
}
```

Both kinds run the same way: in every module that has the hook, in load order, with the same budget and errors. Use `defineHooks` in TypeScript (section 2.6); in JavaScript it is an option that gives the editor the parameter types.

- Call `defineHooks` while the module loads, at its top level. A call from a hook or a timer throws.
- Every property of the object must be a function with a hook name; any other name throws a `TypeError` that names it, and `check` reports it.
- A module may call `defineHooks` more than once with other hooks. A hook defined twice in a module, by two calls or by a call and a top-level function, throws, and the module does not load.
- A method runs with `this` set to the object, a top-level function with `this` set to the module's top level.
- A top-level function, or a variable holding one, with a hook name is a hook of its module, exported or not. Give helpers other names: a helper `onLocalFrame` that another module calls every frame runs twice per frame. `check` warns of an exported hook name.

| Hook | When |
|---|---|
| `onStart()` | once, at the first frame |
| `onFrame(frame)` | every frame, before the triggers |
| `onUnitCreated`, `onUnitCompleted`, `onUnitDied`, `onUnitDamaged`, `onOrderIssued`, `onTargetAcquired`, `onUnitTrained`, `onResearchCompleted`, `onUpgradeCompleted`, `onSpellCast`, `onBulletCreated`, `onBulletHit`, `onOwnerChanged`, `onUnitLoaded`, `onUnitUnloaded`, `onPlayerLeft`, `onPlayerResult` | the events of the frame, one call per event |
| `onBeforeDamage`, `onBeforeOrder`, `onBeforeTargetAcquired` | decisions: inside the game routine, the game read-only; change only the decision |
| `onLocalKeyDown`, `onLocalKeyUp`, `onLocalMouseDown`, `onLocalMouseUp`, `onLocalChat`, `onLocalButtonPressed`, `onLocalButtonTargeted` | input of the local player, before the game handles it; not in replays or for observers |
| `onLocalFrame(frame)` | after each frame this machine plays |
| `onRender(screen)` | after `onLocalFrame`, to draw |

A frame runs, at the frame start before the triggers: `onStart` (first frame only), the due timers, the channel handlers, the event hooks, `onFrame`.

`game.every(frames, callback)` and `game.after(frames, callback)` add timers; `Time.fromSeconds`, `Time.fromMinutes` and `Time.fromMilliseconds` give the frames. Both return a `Timer` with `cancel()`.

A local input hook can call `event.consume()` to keep a key, mouse button, chat line or button press from the game.

A chat line that starts with `/` goes to the client's commands, also when no command has that name, and never reaches `onLocalChat`.

A decision hook changes the decision object: `setAmount` or `cancel` for damage, `setOrder`, `setTarget` or `reject` for an order, `setTarget` for a target.

A command moves its target off ground the unit cannot stand on before `onBeforeOrder` sees it: `getTargetPosition()` is where the unit goes, `getRequestedPosition()` where the player clicked.

`onBeforeOrder` also gets the orders a game routine puts into a unit's queue itself (`isInserted()`), such as a computer unit's `ComputerReturn` to its guard post. A rejected inserted order stays out of the queue, and `onOrderIssued` does not report it. Rejecting `ComputerReturn` keeps computer units that a script leads from walking back.

A damage amount the hook lowers, to 0 too, still takes at least half a hit point per hit, as every weapon hit does in StarCraft; only `cancel` leaves the unit untouched.

### 3.2 From local input to shared code

Local code cannot change the game. It sends a value over a channel, and the channel's handler runs as shared code on every player's game at the frame the game runs the command, in every replay too.

```js
const votes = sync.createChannel((player, value, units) => {
    game.print(player.getName() + ' votes ' + value);
});

defineHooks({
    onLocalChat(event) {
        if (event.getText() === '-easy' || event.getText() === '-hard') {
            votes.send(event.getText().substring(1));
            event.consume();
        }
    }
});
```

- Create channels in shared code, in the same order on every game: at the module top level or in `onStart`.
- The handler gets the sending player, the value, and the live units the player had selected.
- A value is `null`, a boolean, a number, a string, or an array or plain object of these. It comes from another player's game, so check its kind before using it; the declarations give it the type `unknown`.
- `send` in shared code sends once from every human player's game.
- `sync.createPlayerValue(initial)` keeps one value per player on its own channel: `get(player)`, `set(value)` for the sending player, `set(player, value)`.

A script button (section 5.2) uses a channel of its own: `onPressed` is its handler.

### 3.3 Determinism

Shared code must give the same result on every game, or the games split.

- Use `random` (`next()`, `nextInt(bound)`, `chance(probability)`) for random numbers. It is seeded from the game seed and is the same on every game; `Math.random()` draws from it as well. Both are shared code only.
- There is no `Date` and no clock. Count frames: `game.getFrame()`, `game.getElapsedTime()`, timers.
- Local code must not change a variable that shared code reads. Pass local input to shared code through a channel.
- Sprite and image slots can differ between players: the pools also hold the buildings each player remembers in the fog of war. Shared code may show sprites and images but must not let their ids decide the game.
- `local.*` and the local selection differ between players; they are local code only.

### 3.4 Errors

- An error that a hook, timer or handler does not catch ends that call only, and is logged.
- A shared call that exceeds the instruction budget (chapter 7) stops the script for the rest of the game.
- A local call that exceeds it ends that call only; an `onRender` call that exceeds it draws nothing.

## 4. The API at a glance

The upper API is a set of globals over objects: units, players, types. The lower API `core` works on ids and raw values; the upper API is built on it, and scripts may call both.

| Global | What it holds | Example |
|---|---|---|
| `game` | the game: frame, timers, players, units, map, switches, countdown, leaderboard, settings, vision, combat | `game.units.find({type: UnitType.TerranMarine, owner: player})` |
| `local` | what only this machine knows: local player, screen, mouse, local selection, command card state. Local code only | `local.getPlayer()` |
| `display` | presentation: console, stat_txt strings, message lines, sounds, image graphics, command card | `display.messages.clear()` |
| `sync` | channels between the games | `sync.createChannel(handler)` |
| `random` | the script random numbers. Shared code only | `random.nextInt(4)` |
| `log` | the module's log | `log.info('frame', game.getFrame())` |
| `Position`, `Time` | positions in map pixels, durations in frames | `Position.of(320, 640)`, `Time.fromSeconds(10)` |
| `UnitType`, `WeaponType`, `UpgradeType`, `TechType`, `Order`, `ImageType`, `SpriteType`, `FlingyType` | the game data types, one object per id, with their DAT values | `UnitType.ProtossZealot.getMaxHp()` |
| `Color`, `Font`, `Align`, `Key`, `MouseButton`, `Modifier`, `EventKind`, `ButtonCondition`, `ButtonAction`, ... | constant tables | `Color.fromRgb(255, 128, 0)` |
| `core` | the lower API: ids, raw values, frame records | `core.player.getMinerals(0)` |

Types compare with `===`: `unit.getType() === UnitType.TerranMarine`. A table gives a type by id too: `UnitType.get(0)`.

A few objects in use:

```js
const player = game.players.get(0);
const marines = player.units.count(UnitType.TerranMarine);
const spawn = game.map.locations.findByName('Spawn');

log.info(player.getName(), 'has', marines, 'Marines');

if (spawn !== null) {
    game.units.create(UnitType.TerranMarine, player, spawn.getCenter());
}

game.switches.set(3, true);
display.strings.setStatText(1, 'Marine');
```

Positions are map pixels unless a member names tiles; player slots and type ids start at 0. An object of a unit, sprite, image or bullet throws an `Error` once that thing is gone; check `exists()` first when unsure.

`game.map.findWalkablePosition(position, unit)` gives the position a move command to `position` sends the unit to: the position itself, or walkable ground near it on the unit's side. `game.map.isReachable(from, to)` and `unit.canReach(position)` tell whether a ground unit can walk there.

`game.units.create` creates a unit as the Create Unit action does, but a failed creation shows no message and returns `null`. `game.units.findPlacement(type, player, position)` gives the position `create` would place the unit at, or `null`.

Traps (Floor Gun Trap, Floor Missile Trap and the wall traps) are doodads. As in StarCraft, Create Unit does not create them, since their type lacks the units.dat flag Unit Listing; a map that wants them sets it first: `UnitType.SpecialFloorGunTrap.setAvailabilityUnitListing(true)`. A trap is cloaked until a target comes in range: only its owner and players with a detector see it, and it shows itself to attack. For a turret that is always visible, use a unit type that is not a trap, such as a combat unit type with `setCanMove(false)`.

A unit can have stats of its own while it lives: `unit.setMaxHp(hp)` (`null` gives back the type's maximum), `unit.setWeaponRangeBonus(pixels)` and `unit.setSpeedPercent(percent)`. The game uses them wherever it reads the value: repair, healing, regeneration, percentages and the display for the hit points; attacks and target searches for the range; walking and acceleration for the speed.

## 5. Examples

### 5.1 A timer that gives minerals

```js
const bonus = game.every(Time.fromSeconds(30), () => {
    game.players.getHumans().forEach((player) => {
        player.addMinerals(50);
        player.print('+50 minerals');
    });
});

game.after(Time.fromMinutes(10), () => bonus.cancel());
```

Every 30 seconds each human player gets 50 minerals, for 10 minutes.

### 5.2 A command card button

```js
const COST = 100;

display.commandCard.createButton({
    unitType: UnitType.TerranCommandCenter,
    position: 9,
    icon: 0,
    text: 'Call a Marine (100)',
    hotkey: 'M',
    isEnabled: (unit) => unit.getOwner().getMinerals() >= COST,
    onPressed: (player, units) => {
        units.forEach((unit) => {
            if (player.getMinerals() >= COST) {
                player.subtractMinerals(COST);
                game.units.create(UnitType.TerranMarine, player, unit.getPosition());
            }
        });
    }
});
```

- `createButton` is shared code; call it at the module top level or in `onStart`.
- `position` is the card slot 1 to 9; `icon` is a cmdicons frame.
- `isEnabled(unit)` returns `true` (enabled), `false` (gray) or `null` (hidden). It must read only the game, never `local`: every game calls it again when the press runs.
- The card fills each position with the first button that is not hidden; a gray button takes a position too, and a second shown button of the same position moves to the next one. Of several script buttons at one position, all but one must return `null`, not `false`.
- A hotkey presses the first enabled button with that hotkey, in card order. A script button with the hotkey M, S or A loses to Move, Stop and Attack (positions 1 to 3) while they are enabled; give it another letter.
- `onPressed(player, units, target)` is shared code; `units` are the selected units for which `isEnabled` returned `true`.
- `target: true` makes a target button: the player picks a unit or a point, and `target` holds `position` and `unit`.
- `onLocalPressed(event)` runs first, on the pressing machine, for the press and, for a target button, for the target too; `event.getRecord().kind` tells them apart (`InputKind.ButtonPressed`, `InputKind.ButtonTargeted`). `event.consume()` sends nothing; a consumed press of a target button starts no targeting.

### 5.3 Screen text and messages

Shared code shows messages:

```js
function onStart() {
    game.print('Defend the base for 10 minutes.');
    game.players.get(0).print('You are the defender.');
}
```

Local code draws in `onRender`; the drawing stays until the next call:

```js
function onRender(screen) {
    const seconds = game.getElapsedTime();
    const clock = Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');

    screen.drawText(clock, screen.getWidth() / 2, 8, {font: Font.Large, color: Color.Yellow, align: Align.Center});

    const player = local.getPlayer();

    if (player !== null) {
        player.units.getAll().forEach((unit) => {
            if (unit.getType() === UnitType.TerranGhost) {
                screen.world.drawCircle(unit.getX(), unit.getY(), 24, {color: Color.Cyan});
            }
        });
    }
}
```

`screen` draws in interface units (480 high, `screen.getWidth()` wide), `screen.world` in map pixels above the units, `screen.ground` in map pixels above the terrain and below the units, `screen.minimap` in map pixels on the minimap. `drawImage` draws a picture of `resources/`: `game.map.resources.getImage('logo.png')`; with `source: {x, y, width, height}` it draws a part of the picture, as a frame of a sprite sheet.

### 5.4 Reacting to a unit death

```js
defineHooks({
    onUnitDied(event) {
        const killer = event.getKillerPlayer();

        if (event.getUnitType() === UnitType.ZergZergling && killer !== null) {
            killer.addMinerals(5);
        }
    }
});
```

The event holds the values of the moment the unit died: `getUnit()`, `getUnitType()`, `getOwner()`, `getKiller()`, `getKillerPlayer()`, `getWeapon()`, `getPosition()`. The unit may be gone already.

Script damage names its source: `unit.damage(hp, source)` takes hit points off as a hit does after armor and shields, and `unit.kill(source)` kills through the same routine. The source is a unit (its owner is the killing player) or a player; the death event names it, and the kill counts for it. `unit.kill()` without a source kills as Kill Unit does, credited to nobody.

`unit.damage` and `unit.kill` take exactly the given hit points: no shields, no armor, no damage type, and `onBeforeDamage` does not run. `unit.applyDamage(amount, source, options)` hits as a weapon does, through the game's weapon damage routine: `onBeforeDamage` decides the amount, then the hallucination factor, acid spores, the defensive matrix, shields, armor and the damage type against the unit's size apply, and the death and the kill count for the source.

```js
game.every(Time.fromSeconds(1), () => {
    game.units.find({type: UnitType.ZergZergling}).forEach((zergling) => {
        zergling.applyDamage(10, game.players.get(0), {damageType: DamageType.Explosive});
    });
});
```

- `source` is a unit or a player and is needed: a weapon hit always has an attacking player.
- `weapon: WeaponType.GaussRifle` names the weapon in `onBeforeDamage`, `onUnitDamaged` and `onUnitDied` and gives its damage type; the amount stays the given one, without the weapon's upgrade.
- `damageType` is `DamageType.Normal`, `Explosive`, `Concussive`, `IgnoreArmor` or `Independent`; by default the weapon's, without a weapon `Normal`.
- A hit takes at least half a hit point, and an invincible unit takes nothing. Shared code only.

### 5.5 Your own console

Local code reads where things are on the screen, in the units `screen` draws in, at any interface size and zoom:

```js
const card = display.console.getControl(ConsoleControl.CommandCard);

defineHooks({
    onLocalMouseDown(event) {
        const pointer = local.getPointerPosition();
        const area = card.getRectangle();

        if (pointer !== null && area !== null && pointer.x >= area.x && pointer.x < area.x + area.width &&
            pointer.y >= area.y && pointer.y < area.y + area.height) {
            log.info('on the command card');
        }
    }
});
```

- `local.getPointerPosition()` gives the pointer in interface units; `local.getInterfaceWidth()` and `local.getInterfaceHeight()` give the interface size outside `onRender` too.
- `getRectangle()` of a console control and `getChildRectangle(id)` of one of its controls (a command card button, the minimap area) give what the client last drew, or `null` while hidden. `display.console.getMinimapRectangle()` gives the minimap area.
- `ConsoleChild` names the controls of the console dialogs for `setChildVisible`, `setChildPosition`, `restoreChild` and `getChildRectangle`: `ConsoleChild.MinimapArea`, `MinimapTerrain`, `CommandCardButton1` to `CommandCardButton9`, `SelectionWireframe`, `ResourcesCountdown`, `MenuButton` and more. The declarations list them all.
- `setAnchor(ConsoleAnchor.Right)` makes the positions of a control stand at the interface's right edge: a layout made for 640 units stays at the same distance from the right edge at any width. `Left` and `Center` (the default) work the same way.
- `display.console.setMinimapOnly(true)` shows only the minimap: the console art is not drawn and the map shows through it, and every other dialog is hidden unless the script shows it with `getControl(control).setVisible(true)`. The chat text box stays.
- `display.console.setMinimapSize(size)` draws the minimap area `size` units wide and high instead of 128; clicks follow it. The minimap dialog grows with it to `10 + size` by `37 + size` units, the area still at 6, 33 from its top left. At its installed place the dialog keeps its bottom left, so the area stays on the screen; a dialog placed with `setPosition` keeps its top left.

```js
const SIZE = 160;
const minimap = display.console.getControl(ConsoleControl.Minimap);

display.console.setMinimapSize(SIZE);
minimap.setAnchor(ConsoleAnchor.Right);
minimap.setPosition(640 - (10 + SIZE), 480 - (37 + SIZE));
```

The minimap stands in the bottom right corner at any interface width.

### 5.6 Data files

`game.map.resources.readText(name)` reads any file of `resources/` as UTF-8 text, and `readJson(name)` parses a JSON file:

```js
const heroes = game.map.resources.readJson('data/heroes.json');
```

Each `readJson` call returns a new value. Reading works in shared and local code.

### 5.7 Unit graphics

- `game.createEffect(spriteType, position)` creates a sprite that lives as long as its image scripts run; a looping effect never ends by itself. `sprite.remove()` removes the sprite of an effect, its images first, and returns `false` for the sprite of a unit or a bullet. Shared code only.
- `image.setFrame(frame)` draws a frame until the image's own frame changes; `image.holdFrame(frame)` keeps it while the image's iscript runs on, until `holdFrame(null)`. Both change only what is drawn. The frame includes the direction (`image.getDirection()`), and the image's graphics must have it.
- `unit.playAnimation(Animation.CastSpell)` plays an iscript animation as an order does, with the opcodes it has: an attack animation attacks the unit's target. A sprite in a section that must not be broken keeps its animation, and the unit's orders play their own animations again when their state changes. `Init` and `Death` are refused. Shared code only.
- The selection circle, its vertical offset and the health bar width of a sprite type can be set: `spriteType.setSelectionCircle(size)` (0 to 9), `setSelectionCircleOffset(pixels)`, `setHealthBarWidth(pixels)`. A circle takes them when it is shown, a bar when it is drawn. Shared code only.

#### Pictures as graphics

`game.map.resources.getSheet(name, layout)` reads a PNG or DDS picture as frames of one size, row by row from the top left:

```js
const palm = game.map.resources.getSheet('trees/palm.png', {frameWidth: 96, frameHeight: 128, anchorY: 120, shadow: 'trees/palm-shadow.png'});
```

- `frameWidth`, `frameHeight`: the frame size in picture pixels. `frameCount`: every whole frame by default.
- `directions: 17` for an image that turns: north, then clockwise to south; the west half is drawn mirrored.
- `scale`: picture pixels per map pixel, 1 (SD size), 2 (HD2) or 4 (HD).
- `anchorX`, `anchorY`: the pixel placed on the image position, the frame center by default. Sprites sort by their position, so an anchor at the foot of a tree draws units behind it when they stand north of the foot.
- `teamColor`: a picture of the same size whose alpha says how strongly a pixel takes the owner's color. `shadow`: a picture of the same size whose alpha is the shadow.
- `sd`: a GRP of the same frames, drawn in SD instead of the picture in the tileset palette.

A sheet works where a GRP and anims do: `display.images.replace(type, {sheet: palm})`, `replaceUnit`, `image.replaceGraphics`. SD draws the frames in the map's tileset palette; HD and HD2 draw an anim built when the sheet is first drawn.

`frameSets` lets a sheet follow a script with more frame sets than it has: for each frame set the image's iscript plays, the frame set of the sheet that draws it. A sheet of a walking unit with four frame sets can replace the Marine, whose iscript plays thirteen:

```js
display.images.replaceUnit(marine, {sheet: soldier, frameSets: [0, 0, 1, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3]});
```

`replace` and `replaceUnit` take options for the shadow and the remains, `null` to draw nothing:

```js
display.images.replaceUnit(marine, {sheet: soldier}, {shadow: null, remains: null});
display.images.replace(UnitType.TerranMarine.getFlingyType().getSprite().getImage(), null, {remains: null});
```

- `shadow`: the images of the sprite drawn with the shadow draw function, as the shadow the iscript creates below the unit. A sheet with a shadow picture draws its own shadow there already.
- `remains`: what the unit leaves when it dies: the images its sprite gets after the death, as the death animation, and the sprites those images create, as the remnant on the ground. They last until those sprites are gone.
- `images` `null` keeps the unit's own graphics. A unit's options win over those of its image type; `restore` and `restoreUnit` take back all three.

`image.play({frameSets, frameTime, graphics, loop})` shows frame sets one after another, each for `frameTime` game frames, then the image's own frame again. The direction of a turning image is added. `image.stopPlaying()` ends it; `setFrame` and `holdFrame` end it too. It changes only what is drawn and works in shared and local code:

```js
hero.getSprite().getMainImage().play({graphics: {sheet: castSheet}, frameSets: [0, 1, 2], frameTime: 4});
```

#### Types of your own

A map can register image, sprite and flingy types that draw its sheets. Register them at the module top level or in `onStart`, in shared code; the same calls give the same types in every game and replay.

```js
const palmImage = ImageType.create({sheet: palm, name: 'Palm'});
const palmSprite = SpriteType.create({image: palmImage, name: 'Palm'});

function onStart() {
    game.map.resources.readJson('data/palms.json').forEach((point) => game.createEffect(palmSprite, Position.of(point.x, point.y)));
}
```

- `ImageType.create({sheet, name, script, frameSets, animation, drawFunction})`: `script` is `ImageScript.Still` (frame 0 until Death), `ImageScript.Projectile` (a bullet), `ImageScript.Unit` (a unit without sounds, below) or an image type whose iscript, draw function, overlays and flags the new type copies; a copied unit script plays that unit's sounds too. `frameSets` gives, for each frame set the script plays, the frame set of the sheet that draws it. `animation: {frameSets, frameTime}` loops frame sets for every image of the type, as presentation.
- `SpriteType.create({image, name, selectionCircle, selectionCircleOffset, healthBarWidth, visible})`.
- `FlingyType.create({sprite, name, base, topSpeed, acceleration, haltDistance, turnRate, movementControl})`: the motion values of `base`, then those given.
- The game reads the frames of a registered image type: a sprite of the type gets the canvas of the sheet, which decides where it is seen and clicked.
- An effect of a registered sprite type is a sprite like a spell's: it sorts with units, takes the fog of the tiles it covers, and shows a shadow when the sheet has one. `sprite.playAnimation(Animation.Death)` ends a `Still` sprite a frame later.
- `UnitType.TerranMarine.setFlingyType(FlingyType.create({sprite, base: UnitType.TerranMarine.getFlingyType()}))` gives the units created afterwards the new look; with `script: ImageType.get(...)` they walk, attack and die as that image does. The portrait, wireframe and command icon stay those of the unit type.
- `ImageScript.Unit` is the script of a unit without sounds. It needs a sheet of 17 directions and plays 15 frame sets: 0 stand, 1 to 8 walk (one per frame), 9 aim and 10 fire (an attack fires the weapon the target needs, a spell casts the same way), 11 to 14 death (two frames each, then the unit is gone). A sheet with fewer frame sets maps them with `frameSets`. It does not move the unit: give the unit a flingy type that moves by flingy.dat, as the SCV's.

```js
const driller = ImageType.create({sheet: drillerSheet, script: ImageScript.Unit, frameSets: [0, 1, 2, 3, 4, 1, 2, 3, 4, 5, 6, 7, 7, 7, 7]});
const drillerSprite = SpriteType.create({image: driller, selectionCircle: 1, healthBarWidth: 24});

UnitType.TerranSCV.setFlingyType(FlingyType.create({sprite: drillerSprite, base: UnitType.TerranSCV.getFlingyType()}));
```

- `game.createBullet(weapon, source, position, {target, flingy, direction})` fires a bullet at a unit or a position with a flingy type of your own; `bullet.detonate()` ends it as it ends at its target, and its Death applies the weapon.

### 5.8 Sounds

`display.sound.playAt(sound, position)` plays a sound of `sfxdata.dat` by its id, or a sound file of `resources/`:

```js
const alarm = game.map.resources.getSound('sounds/alarm.wav');

defineHooks({
    onUnitDied(event) {
        display.sound.playAt(alarm, event.getPosition());
    }
});
```

- `getSound` reads a RIFF WAVE of 8-bit, 16-bit or 24-bit PCM, mono or stereo, or an Ogg Vorbis file; another file throws an `Error`.
- A sound file plays on the channels of Play WAV, at full volume on the screen and fading to silence 512 pixels outside it, whether the players see the position or not.
- Shared code plays it on every player's game. Local code plays it on this machine only, with the sounds of the next frame start.

### 5.9 Terrain

`game.map.terrain` changes the tiles of the map in shared code. The changes apply together at the next trigger step, as on a map that had the tiles from the start:

```js
const wall = game.map.tileset.getTile(12, 0);
const tiles = [];

for (let index = 0; index < 96 * 96; index++) {
    tiles.push(wall);
}

game.map.terrain.setTiles(0, 0, 96, 96, tiles);
game.map.terrain.fillTiles(10, 4, 3, 2, wall);
game.map.terrain.copyTiles(0, 0, 8, 8, 40, 40);
```

- `setTile(tileX, tileY, tile)` sets one tile. `setTiles(left, top, width, height, tiles)` sets a rectangle in one call, from Tiles or tile ids row by row; a 96 by 96 rectangle costs one call.
- `fillTiles` and `copyTiles` take a location, or a rectangle of tiles (`left, top, width, height`). A rectangle that leaves the map throws a `RangeError` and changes nothing.
- Ground units on tiles that became unwalkable move to the nearest free walkable ground and start their current order again, so a moving unit keeps moving to its target. Buildings stay.
- `terrain.creep.fillTiles(left, top, width, height)` or `fillTiles(location)` adds creep to every tile of the area that can take it, as a finished building does under its footprint, and returns the number of tiles that took creep.

`replaceTileImage(tile, image)` gives a tile's megatile a picture of `resources/`, at the module top level or in `onStart`:

```js
game.map.terrain.replaceTileImage(game.map.tileset.getTile(12, 0), game.map.resources.getImage('tiles/wall.png'));
```

- Every tile that shows the same megatile shows the picture; pick a tile the map does not use elsewhere, then place it with `setTile`.
- The picture is 32, 64 or 128 pixels square (any multiple of 32). HD and HD2 draw it stretched over the tile with its alpha, over the installed tile; SD draws its opaque pixels in the tileset palette.
- The picture changes only what is drawn: walkability, buildability and sight stay those of the tile.

## 6. Map protection

```powershell
SC4J.bat tool scr-enhanced script pack Map.scx --script my-map -o Protected.scx --protect
```

What `--protect` does:

- Each script loses its comments and spare whitespace, and the names declared inside functions and blocks get short names. The script runs as the original does.
- Every line of the protected script comes from one original line, so error lines can be traced back.
- The map is marked protected, and the replays of its games keep the mark. `unpack` refuses a protected map.
- The source map `Protected.scx.sourcemap.json` is written next to the output. The map does not hold it; keep it to yourself.

What it does not do:

- It does not encrypt. Names that are looked up by name stay: top-level functions and variables of a module, hooks, `module.exports`, `exports`, `require` ids, property names, the API, and the text of strings.
- The files of `resources/` are stored as they are.

To read an error of a protected map, trace its location with the source map:

```powershell
SC4J.bat tool scr-enhanced script trace Protected.scx.sourcemap.json --at main.js:3
SC4J.bat tool scr-enhanced script trace Protected.scx.sourcemap.json --log sc4j.log
```

## 7. Limits

- Language: Rhino 1.8.1 has no `class`, `async`/`await`, spread (`...`), `import`/`export` or `for (const ...)` in any form (`of`, `in` or a counting loop); use `let`. A `const` declared in a loop body keeps its first value; use `let`. The functions created in the body of a `for (let ...)` loop share one binding of its variables; copy the variable into a `let` inside the body. `check` reports these. TypeScript compiled with target ES5 has none of these limits (section 2.6).
- Promises: `Promise` works. Its jobs (`then`, `catch`, `finally`) run when the call into the script that queued them returns (the loading, a hook, a timer or a channel handler), within the same instruction budget. Promises do not wait for time: use `game.after`.
- Globals: no `eval`, no `Function` from strings, no `Date`, no `setTimeout` or other timers, no Java access. `Math` gives the same results on every platform.
- Instruction budget: each call into the script (loading, a frame, a decision, a local input, a local frame, a render) may run 5,000,000 interpreter instructions; function calls may nest 10,000 deep. That is about 200,000 to 600,000 steps of a short loop, or about 40,000 calls of a small function: a pass over every tile of a large map with a call per tile can take a whole frame. Use lookup tables instead of calls in such passes, and spread long work over frames with timers.
- Terrain: buildings stay when the tiles under them change; StarCraft has no terrain change, so nothing moves or removes a building that stands on tiles that became unbuildable.
- Script buttons: at most 256 per game.
- Types of your own: 256 image types, 256 sprite types and 128 flingy types per game. A sheet's canvas is at most 255 by 255 map pixels, a picture at most 4096 by 4096 pixels, and the graphics made of a map's sheets at most 256 MiB, one byte per texel and layer of each distinct frame they show (frames no graphics shows take nothing).
- Effects take sprite, image and effect slots that spells and dust use too; `createEffect` returns `null` when they are full.
- Saved games: a game with a script cannot be saved.
- Start units: as in StarCraft, a Use Map Settings game gives every player whose race in the map is User Selectable a town hall and four workers, with or without triggers, and keeps only the neutral preplaced units of those players. Set fixed races in the map, or remove the units in `onStart`.
