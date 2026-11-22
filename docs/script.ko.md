# Enhanced Script

[English](script.md) | **한국어**

SC:R Enhanced의 맵 스크립트를 쓰는 방법입니다. 전체 API 레퍼런스는 선언 파일 [`scr-enhanced.d.ts`](../src/mod/resources/org/fogofwarlabs/sc4j/screnhanced/script/scr-enhanced.d.ts)와 [`core.d.ts`](../src/mod/resources/org/fogofwarlabs/sc4j/screnhanced/script/core.d.ts)입니다. `init`이 새 스크립트 폴더마다 두 파일을 복사합니다.

- [1. 개요](#1-개요)
- [2. 시작하기](#2-시작하기)
- [3. 공유 코드와 로컬 코드](#3-공유-코드와-로컬-코드)
- [4. API 한눈에 보기](#4-api-한눈에-보기)
- [5. 예제](#5-예제)
- [6. 맵 보호](#6-맵-보호)
- [7. 제한](#7-제한)

## 1. 개요

맵 스크립트는 맵 파일 안에 저장된 JavaScript입니다. SC:R Enhanced는 [Rhino](https://github.com/mozilla/rhino) 1.8.1의 인터프리터 모드로, Rhino가 지원하는 가장 새 ECMAScript 수준에서 실행합니다. 없는 문법과 `Promise` 작업이 실행되는 때는 7장에 있습니다.

- 맵 파일의 원래 데이터는 그대로 두고, 스크립트 패키지(매니페스트, 스크립트, `resources/`의 파일)를 그 뒤에 저장합니다.
- 스크립트는 모든 플레이어의 게임에서 락스텝으로 실행됩니다. 같은 코드가 같은 프레임에 같은 결과를 냅니다. 게임의 모든 플레이어가 SC:R Enhanced를 실행합니다.
- 리플레이는 스크립트를 담고 있어 다시 실행합니다. 리소스는 맵에서 읽으므로 맵을 `<install>/userdata/mods/scr-enhanced/maps/`에 두세요.
- 스크립트가 있는 게임은 저장할 수 없습니다.

## 2. 시작하기

### 2.1 스크립트 도구

스크립트 도구는 모드 `scr-enhanced`의 도구 `script`입니다. SC4J 설치본의 `SC4J.bat`(Linux `SC4J`, Windows 콘솔 `SC4J-cli.exe`)으로 실행합니다.

```powershell
SC4J.bat tool scr-enhanced script <command> [arguments]
```

상대 경로는 현재 폴더 기준입니다.

| 명령 | 하는 일 |
|---|---|
| `init <folder>` | 새 스크립트 폴더를 만듦. 폴더는 없거나 비어 있어야 함 |
| `check <folder>` | 스크립트 폴더의 오류와 경고를 출력 |
| `run <map> --script <folder> [--melee] [--watch]` | 폴더를 맵 복사본에 넣고 그 맵으로 SC:R Enhanced를 시작 |
| `pack <map> --script <folder> -o <output>` | 스크립트 패키지를 넣은 맵을 `<output>`에 씀. 이전 패키지는 교체 |
| `pack <map> --script <folder> -o <output> --protect` | 같은 일을 보호된 맵으로(6장) |
| `unpack <map> -o <folder>` | 맵의 스크립트 패키지를 없거나 빈 폴더에 씀 |
| `trace <source map> --at <script>:<line>` | 보호된 스크립트의 줄에 해당하는 원래 줄을 출력 |
| `trace <source map> --log <file>` | 로그 같은 파일을 원래 줄로 바꿔 출력 |

`run`은 맵 설정 사용 게임을, `--melee`를 주면 무작위 종족 컴퓨터 하나와 하는 밀리 게임을 시작합니다. 맵 복사본은 `<install>/userdata/mods/scr-enhanced/script-run/`에 놓이고 원본 맵은 그대로입니다. `--watch`를 주면 폴더가 바뀔 때 다시 넣고, 맵을 다시 시작하면 불러옵니다. 명령은 게임이 끝나면 끝납니다. `--install <folder>`로 다른 SC4J 설치본을 지정합니다.

이 저장소를 복제했다면 같은 명령을 Gradle로 실행할 수 있습니다. 경로는 저장소 루트 기준입니다.

```powershell
.\gradlew.bat runScriptTool --args="init my-map"
```

### 2.2 스크립트 폴더

```powershell
SC4J.bat tool scr-enhanced script init my-map
```

| 파일 | 내용 |
|---|---|
| `manifest.json` | `{"name": "my-map", "main": "main.js", "api": "1"}`: 패키지 이름(폴더 이름), 먼저 실행할 스크립트, 스크립트 API 버전 |
| `main.js` | 메인 스크립트. `onStart`와 `onFrame` 예제 |
| `jsconfig.json` | 편집기 설정. 선언 파일만 쓰고, 스크립트를 검사하고, 파일마다 별도 모듈 |
| `scr-enhanced.d.ts` | 상위 API, 런타임, 표준 객체의 선언 |
| `core.d.ts` | 하위 API `core`의 선언 |
| `resources/` | 스크립트가 읽는 파일: GRP, anim, PNG, DDS, 그리고 텍스트나 JSON으로 읽는 모든 파일 |

맵에는 `manifest.json`, `resources/` 밖의 `.js` 파일, `resources/` 아래 파일만 들어갑니다.

### 2.3 편집기 자동 완성

Visual Studio Code처럼 TypeScript 기반 JavaScript 지원이 있는 편집기로 폴더를 엽니다. `jsconfig.json` 덕분에 브라우저나 Node.js 전역 없이 `scr-enhanced.d.ts`와 `core.d.ts` 기준으로 자동 완성과 검사가 됩니다.

최상위 함수 훅의 매개변수 타입은 JSDoc으로 적습니다. `defineHooks`(3.1절)의 메서드는 JSDoc 없이 선언에서 타입을 받습니다.

```js
/** @param {UnitDiedEvent} event */
function onUnitDied(event) {
    log.info(event.getUnitType().getName());
}
```

### 2.4 모듈

`.js` 파일마다 자체 최상위 범위를 가진 모듈입니다. `require`는 다른 스크립트를 한 번 불러와 그 `module.exports`를 돌려줍니다. `./`와 `../`는 부르는 스크립트의 폴더 기준, 다른 id는 스크립트 폴더 기준이고, `.js`는 생략할 수 있습니다. `exports`에 속성을 더하거나, 다른 값을 내보내려면 `module.exports`에 대입합니다.

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

모든 모듈이 훅을 정의할 수 있고, 훅은 그 훅이 있는 모든 모듈에서 불러온 순서대로 실행됩니다. 훅 이름을 가진 최상위 함수나 함수를 담은 변수는 모듈이 내보내더라도 그 모듈의 훅이므로, 도우미 함수에는 다른 이름을 붙이세요. `check`는 내보낸 훅 이름을 경고합니다. `require`는 공유 코드에서 부릅니다(3장). 로컬 코드는 새 모듈을 불러올 수 없습니다.

### 2.5 로그

`log.info`, `log.warn`, `log.error`는 모듈 이름을 앞에 붙여 SC:R Enhanced 로그 `<install>/userdata/logs/scr-enhanced/sc4j.log`에 씁니다. 스크립트 오류도 오류를 던진 스크립트 파일과 줄(`main.js:12`)과 함께 여기에 남습니다. API 호출이 던진 오류는 그 호출의 줄이 남습니다.

### 2.6 TypeScript

스크립트를 TypeScript로 쓰고 `tsc`로 컴파일할 수 있습니다(TypeScript 5.9로 확인). 맵에는 컴파일한 JavaScript만 들어갑니다.

```text
my-map/                 TypeScript 프로젝트
  tsconfig.json
  src/main.ts           소스
  src/bonus.ts
  script/               팩할 스크립트 폴더: init으로 만들고 tsc가 채움
    manifest.json       {"name": "my-map", "main": "main.js", "api": "1"}
    scr-enhanced.d.ts
    core.d.ts
    resources/
```

`init my-map/script`로 `script/`를 만들고, `manifest.json`의 이름을 정하고, 그 안의 `main.js`와 `jsconfig.json`을 지운 뒤 `my-map/tsconfig.json`을 추가하세요.

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

- tsc는 `outDir`의 파일을 `include`에서 빼므로 선언 파일은 `files`에 적습니다.
- tsc는 `main.js`와 다른 모듈을 `manifest.json` 옆에 쓰고 다른 파일은 복사하지 않습니다. `manifest.json`과 `resources/`는 `script/`에 그대로 둡니다.
- 훅은 `defineHooks`(3.1절)로 정의하세요. tsc가 매개변수 타입을 정하고, 잘못 쓴 훅 이름을 알려 주며, `noUnusedLocals`에서도 쓰지 않는 훅으로 보고하지 않습니다.
- 모듈은 상대 경로(`./bonus`)로 가져오세요.

```powershell
tsc -p my-map
SC4J.bat tool scr-enhanced script check my-map/script
SC4J.bat tool scr-enhanced script pack Map.scx --script my-map/script -o Out.scx
```

`tsc -w -p my-map`을 `run Map.scx --script my-map/script --watch`와 함께 쓰면 컴파일할 때마다 맵을 다시 시작할 때 불러옵니다. `--protect`도 출력에 그대로 쓸 수 있습니다.

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

최상위 함수 훅도 동작하지만, tsc는 런타임이 그 함수를 부른다는 것을 모릅니다. `noUnusedLocals`가 훅을 보고하고, 훅 이름을 잘못 써도 알 수 없습니다. 훅은 내보내지 말고, 도우미 함수에는 훅 이름이 아닌 이름을 붙이세요. 훅 이름을 가진 최상위 함수나 함수를 담은 변수는 내보내든 아니든 그 모듈의 훅입니다. 다른 모듈이 매 프레임 부르는 도우미 `onLocalFrame`은 프레임마다 두 번 실행됩니다. `check`는 내보낸 훅 이름을 경고합니다.

target ES5에서 되는 것(tsc가 각 파일에 도우미 함수를 넣어 ES5로 씁니다):

- 상속, `super`, getter, static 멤버가 있는 클래스
- 화살표 함수, 템플릿 문자열, 나머지 매개변수와 기본값 매개변수
- 반복문 안에서 클로저와 함께 쓰는 것을 포함한 `let`과 `const`
- 기본값과 나머지 요소가 있는 구조 분해
- 호출, 배열, 객체의 전개 구문
- 옵셔널 체이닝 `?.`과 `??`
- enum, const enum, namespace
- 제너레이터
- 런타임 `Promise` 위의 `async`와 `await`. 작업은 7장에서 말한 때에 실행됩니다.
- 배열에 대한 `for...of`. `Map`, `Set`, 문자열은 `"downlevelIteration": true`일 때
- 모듈 사이의 import, default import 포함

안 되는 것:

- ES2015 이후 target은 Rhino에 없는 형태를 그대로 둡니다: ES2015부터 `class`, 전개 구문, 구조 분해의 나머지 요소, `for (const ...)`, ES2017부터 `async` 함수, ES2018부터 객체 전개. 반복문 안의 `let`과 `const`는 Rhino의 반복문 규칙(7장)을 따릅니다. `check`가 이것들을 알려 줍니다.
- `noEmitHelpers`는 도우미 함수를 빼므로 스크립트가 `ReferenceError`로 실패합니다. `importHelpers`는 도우미 함수를 `tslib` 모듈에서 불러오므로 npm 패키지 `tslib`의 `tslib.js`를 스크립트 폴더에 복사하세요.
- `commonjs`가 아닌 `module`, `import()`, 브라우저와 Node.js 라이브러리.
- 오류와 로그는 TypeScript 소스가 아니라 컴파일한 JavaScript의 줄(`main.js:72`)을 알려 줍니다.

## 3. 공유 코드와 로컬 코드

| | 공유 코드 | 로컬 코드 |
|---|---|---|
| 위치 | 모듈 최상위, `onStart`, `onFrame`, 타이머, 이벤트 훅, 결정 훅, 채널 핸들러, 스크립트 버튼의 `onPressed` | `onLocalFrame`, `onRender`, `onLocal*` 입력 훅, 스크립트 버튼의 `onLocalPressed` |
| 실행 | 모든 플레이어의 게임과 모든 리플레이에서 똑같이 | 이 플레이어의 컴퓨터에서만 |
| 할 수 있음 | 게임 변경, 타이머 추가, 채널과 버튼 생성, 난수 뽑기, 모듈 불러오기 | 게임 읽기, `local` 사용, 그리기, 로컬 화면 이동, 채널로 보내기 |
| 할 수 없음 | `local` 사용(`TypeError`) | 게임 변경, 타이머 추가와 취소, 채널 생성, 난수 뽑기, 모듈 불러오기(모두 `TypeError`) |

선언에 "Shared code only." 또는 "Local code only."가 붙은 멤버는 반대쪽에서 `TypeError`를 던지고, "Only in onRender."는 `onRender` 밖에서 던집니다.

### 3.1 훅

훅은 정해진 이름의 함수입니다. `defineHooks`에 넘긴 객체의 메서드나 최상위 함수로 정의합니다.

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

두 방식은 똑같이 실행됩니다. 그 훅이 있는 모든 모듈에서 불러온 순서대로, 같은 예산과 오류 처리로 실행됩니다. TypeScript에서는 `defineHooks`를 쓰세요(2.6절). JavaScript에서는 편집기가 매개변수 타입을 알게 해 주는 선택지입니다.

- `defineHooks`는 모듈을 불러오는 동안, 모듈 최상위에서 부릅니다. 훅이나 타이머에서 부르면 오류를 던집니다.
- 객체의 모든 속성은 훅 이름을 가진 함수여야 합니다. 다른 이름이 있으면 그 이름을 알려 주는 `TypeError`를 던지고, `check`도 알려 줍니다.
- 한 모듈에서 `defineHooks`를 다른 훅으로 여러 번 부를 수 있습니다. 한 모듈에서 같은 훅을 두 번 정의하면(두 번의 호출, 또는 호출과 최상위 함수) 오류를 던지고 모듈을 불러오지 못합니다.
- 메서드는 `this`가 그 객체인 채로, 최상위 함수는 `this`가 모듈 최상위인 채로 실행됩니다.

| 훅 | 호출 시점 |
|---|---|
| `onStart()` | 첫 프레임에 한 번 |
| `onFrame(frame)` | 매 프레임, 트리거보다 먼저 |
| `onUnitCreated`, `onUnitCompleted`, `onUnitDied`, `onUnitDamaged`, `onOrderIssued`, `onTargetAcquired`, `onUnitTrained`, `onResearchCompleted`, `onUpgradeCompleted`, `onSpellCast`, `onBulletCreated`, `onBulletHit`, `onOwnerChanged`, `onUnitLoaded`, `onUnitUnloaded`, `onPlayerLeft`, `onPlayerResult` | 프레임의 이벤트마다 한 번 |
| `onBeforeDamage`, `onBeforeOrder`, `onBeforeTargetAcquired` | 결정. 게임 루틴 안에서, 게임은 읽기 전용. 결정만 바꿈 |
| `onLocalKeyDown`, `onLocalKeyUp`, `onLocalMouseDown`, `onLocalMouseUp`, `onLocalChat`, `onLocalButtonPressed`, `onLocalButtonTargeted` | 로컬 플레이어의 입력, 게임이 처리하기 전. 리플레이와 관전자에게는 없음 |
| `onLocalFrame(frame)` | 이 컴퓨터가 프레임을 진행할 때마다 그 뒤에 |
| `onRender(screen)` | `onLocalFrame` 뒤, 그리기용 |

프레임 시작 시 트리거보다 먼저 `onStart`(첫 프레임만), 만기된 타이머, 채널 핸들러, 이벤트 훅, `onFrame` 순으로 실행됩니다.

`game.every(frames, callback)`과 `game.after(frames, callback)`은 타이머를 추가합니다. 프레임 수는 `Time.fromSeconds`, `Time.fromMinutes`, `Time.fromMilliseconds`로 구합니다. 둘 다 `cancel()`이 있는 `Timer`를 돌려줍니다.

로컬 입력 훅은 `event.consume()`으로 키, 마우스 버튼, 채팅 줄, 버튼 누름을 게임이 처리하지 않게 막을 수 있습니다.

결정 훅은 결정 객체를 바꿉니다. 피해는 `setAmount`나 `cancel`, 명령은 `setOrder`, `setTarget`, `reject`, 대상은 `setTarget`입니다. 그동안 게임은 읽기 전용입니다.

### 3.2 로컬 입력을 공유 코드로

로컬 코드는 게임을 바꿀 수 없습니다. 대신 채널로 값을 보내면, 게임이 그 명령을 실행하는 프레임에 채널 핸들러가 모든 플레이어의 게임에서 공유 코드로 실행됩니다. 리플레이에서도 실행됩니다.

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

- 채널은 공유 코드에서 모든 게임이 같은 순서로 만듭니다. 모듈 최상위나 `onStart`에서 만드세요.
- 핸들러는 보낸 플레이어, 값, 그 플레이어가 선택했던 살아 있는 유닛을 받습니다.
- 값은 `null`, 불리언, 숫자, 문자열, 또는 이것들로 된 배열이나 일반 객체입니다. 값은 다른 플레이어의 게임에서 오므로 쓰기 전에 종류를 확인하세요. 선언 파일은 값의 타입을 `unknown`으로 줍니다.
- 공유 코드에서 `send`하면 사람 플레이어의 게임마다 한 번씩 보냅니다.
- `sync.createPlayerValue(initial)`은 자체 채널로 플레이어마다 값 하나를 둡니다. `get(player)`, 보낸 플레이어의 `set(value)`, `set(player, value)`가 있습니다.

스크립트 버튼(5.2절)은 자체 채널을 쓰며 `onPressed`가 그 핸들러입니다.

### 3.3 결정성

공유 코드는 모든 게임에서 같은 결과를 내야 합니다. 그렇지 않으면 게임이 갈라집니다.

- 난수는 `random`(`next()`, `nextInt(bound)`, `chance(probability)`)을 씁니다. 게임 시드로 시작해 모든 게임에서 같습니다. `Math.random()`도 같은 난수를 씁니다. 둘 다 공유 코드 전용입니다.
- `Date`와 시계는 없습니다. 프레임으로 셉니다: `game.getFrame()`, `game.getElapsedTime()`, 타이머.
- 로컬 코드는 공유 코드가 읽는 변수를 바꾸면 안 됩니다. 로컬 입력은 채널로 공유 코드에 넘깁니다.
- 스프라이트와 이미지 슬롯은 플레이어마다 다를 수 있습니다. 각 플레이어가 전장의 안개 속에 기억하는 건물도 그 풀에 있기 때문입니다. 공유 코드는 스프라이트와 이미지를 보여 줄 수는 있지만 그 id로 게임을 결정하면 안 됩니다.
- `local.*`과 로컬 선택은 플레이어마다 다르므로 로컬 코드 전용입니다.

### 3.4 오류

- 훅, 타이머, 핸들러가 잡지 않은 오류는 그 호출만 끝내고 로그에 남습니다.
- 공유 호출이 명령어 예산(7장)을 넘으면 그 게임이 끝날 때까지 스크립트가 멈춥니다.
- 로컬 호출이 넘으면 그 호출만 끝납니다. `onRender` 호출이 넘으면 아무것도 그리지 않습니다.

## 4. API 한눈에 보기

상위 API는 유닛, 플레이어, 타입 같은 객체를 다루는 전역들입니다. 하위 API `core`는 id와 원시 값을 다룹니다. 상위 API는 하위 API 위에 만들어졌고, 스크립트는 둘 다 쓸 수 있습니다.

| 전역 | 내용 | 예 |
|---|---|---|
| `game` | 게임: 프레임, 타이머, 플레이어, 유닛, 맵, 스위치, 카운트다운, 리더보드, 설정, 시야, 전투 | `game.units.find({type: UnitType.TerranMarine, owner: player})` |
| `local` | 이 컴퓨터만 아는 것: 로컬 플레이어, 화면, 마우스, 로컬 선택, 커맨드 카드 상태. 로컬 코드 전용 | `local.getPlayer()` |
| `display` | 표시: 콘솔, stat_txt 문자열, 메시지 줄, 소리, 이미지 그래픽, 커맨드 카드 | `display.messages.clear()` |
| `sync` | 게임 사이의 채널 | `sync.createChannel(handler)` |
| `random` | 스크립트 난수. 공유 코드 전용 | `random.nextInt(4)` |
| `log` | 모듈의 로그 | `log.info('frame', game.getFrame())` |
| `Position`, `Time` | 맵 픽셀 단위 위치, 프레임 단위 시간 | `Position.of(320, 640)`, `Time.fromSeconds(10)` |
| `UnitType`, `WeaponType`, `UpgradeType`, `TechType`, `Order`, `ImageType`, `SpriteType`, `FlingyType` | 게임 데이터 타입. id마다 객체 하나, DAT 값 포함 | `UnitType.ProtossZealot.getMaxHp()` |
| `Color`, `Font`, `Align`, `Key`, `MouseButton`, `Modifier`, `EventKind`, `ButtonCondition`, `ButtonAction`, ... | 상수 테이블 | `Color.fromRgb(255, 128, 0)` |
| `core` | 하위 API: id, 원시 값, 프레임 기록 | `core.player.getMinerals(0)` |

타입은 `===`로 비교합니다: `unit.getType() === UnitType.TerranMarine`. 테이블은 id로도 타입을 줍니다: `UnitType.get(0)`.

객체를 쓰는 예:

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

멤버가 타일을 명시하지 않으면 위치는 맵 픽셀입니다. 플레이어 슬롯과 타입 id는 0부터 셉니다. 유닛, 스프라이트, 이미지, 총알 객체는 그 대상이 사라지면 `Error`를 던집니다. 확실하지 않으면 먼저 `exists()`를 확인하세요.

멤버마다 인자와 주의 사항은 선언 파일을 보세요. 편집기가 자동 완성에서 보여 줍니다.

## 5. 예제

### 5.1 미네랄을 주는 타이머

```js
const bonus = game.every(Time.fromSeconds(30), () => {
    game.players.getHumans().forEach((player) => {
        player.addMinerals(50);
        player.print('+50 minerals');
    });
});

game.after(Time.fromMinutes(10), () => bonus.cancel());
```

10분 동안 30초마다 사람 플레이어마다 미네랄 50을 줍니다.

### 5.2 커맨드 카드 버튼

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

- `createButton`은 공유 코드입니다. 모듈 최상위나 `onStart`에서 부르세요. 게임마다 스크립트 버튼은 최대 256개입니다.
- `position`은 카드 칸 1~9, `icon`은 cmdicons 프레임입니다.
- `isEnabled(unit)`은 `true`(활성), `false`(회색), `null`(숨김)을 돌려줍니다. 버튼이 눌리면 모든 게임이 다시 부르므로 `local`이 아닌 게임만 읽어야 합니다.
- `onPressed(player, units, target)`은 공유 코드이고, `units`는 선택한 유닛 중 `isEnabled`가 `true`를 돌려준 유닛입니다.
- `target: true`면 대상 버튼입니다. 플레이어가 유닛이나 지점을 고르고, `target`에 `position`과 `unit`이 들어옵니다.
- `onLocalPressed(event)`는 누른 컴퓨터에서 먼저 실행됩니다. 누를 때 불리고, 대상 버튼은 대상을 고를 때도 불립니다. 둘은 `event.getRecord().kind`(`InputKind.ButtonPressed`, `InputKind.ButtonTargeted`)로 구분합니다. `event.consume()`하면 아무것도 보내지 않고, 대상 버튼의 누름을 소비하면 대상 지정이 시작되지 않습니다.

### 5.3 화면 텍스트와 메시지

공유 코드는 메시지를 보여 줍니다.

```js
function onStart() {
    game.print('Defend the base for 10 minutes.');
    game.players.get(0).print('You are the defender.');
}
```

로컬 코드는 `onRender`에서 그립니다. 그린 것은 다음 호출까지 남습니다.

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

`screen`은 인터페이스 단위(높이 480, 너비 `screen.getWidth()`)로, `screen.world`는 유닛 위에 맵 픽셀로, `screen.minimap`은 미니맵에 맵 픽셀로 그립니다. `drawImage`는 `resources/`의 그림을 그립니다: `game.map.resources.getImage('logo.png')`. `source: {x, y, width, height}`를 주면 스프라이트 시트의 한 프레임처럼 그림의 일부만 그립니다.

### 5.4 유닛 죽음에 반응하기

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

이벤트에는 유닛이 죽은 순간의 값이 있습니다: `getUnit()`, `getUnitType()`, `getOwner()`, `getKiller()`, `getKillerPlayer()`, `getWeapon()`. 유닛은 이미 사라졌을 수 있습니다.

스크립트가 준 피해에도 출처를 붙일 수 있습니다. `unit.damage(hp, source)`는 방어력과 실드를 거친 공격처럼 체력을 깎고, `unit.kill(source)`는 같은 루틴으로 유닛을 죽입니다. 출처는 유닛(그 주인이 죽인 플레이어)이나 플레이어입니다. 죽음 이벤트에 출처가 남고 처치 수도 출처에 올라갑니다. 출처 없는 `unit.kill()`은 Kill Unit처럼 아무에게도 처치를 주지 않습니다.

### 5.5 나만의 콘솔

로컬 코드는 화면 위치를 `screen`이 그리는 단위로 읽습니다. 인터페이스 크기와 확대 배율에 상관없이 맞습니다.

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

- `local.getPointerPosition()`은 포인터를 인터페이스 단위로 줍니다. `local.getInterfaceWidth()`와 `local.getInterfaceHeight()`는 `onRender` 밖에서도 인터페이스 크기를 줍니다.
- 콘솔 컨트롤의 `getRectangle()`과 그 안 컨트롤의 `getChildRectangle(id)`(명령 카드 버튼, 미니맵 영역)는 클라이언트가 마지막으로 그린 사각형을 줍니다. 숨겨져 있으면 `null`입니다. `display.console.getMinimapRectangle()`은 미니맵 영역을 줍니다.
- `ConsoleChild`는 `setChildVisible`, `setChildPosition`, `restoreChild`, `getChildRectangle`에 쓰는 콘솔 대화 상자 컨트롤의 이름입니다: `ConsoleChild.MinimapArea`, `MinimapTerrain`, `CommandCardButton1`~`CommandCardButton9`, `SelectionWireframe`, `ResourcesCountdown`, `MenuButton` 등. 전체 목록은 선언 파일에 있습니다.
- `setAnchor(ConsoleAnchor.Right)`를 쓰면 컨트롤의 위치가 인터페이스 오른쪽 가장자리를 기준으로 놓입니다. 640 단위에 맞춘 배치가 어떤 너비에서도 오른쪽 가장자리와 같은 거리에 있습니다. `Left`와 `Center`(기본값)도 같은 방식입니다.
- `display.console.setMinimapSize(size)`는 미니맵 영역을 128 대신 가로세로 `size` 단위로 그립니다. 클릭도 그 크기를 따릅니다. 미니맵 대화 상자도 함께 커져 가로 `10 + size`, 세로 `37 + size` 단위가 되고, 영역은 여전히 대화 상자 왼쪽 위에서 6, 33에 있습니다. 원래 자리에서는 대화 상자의 왼쪽 아래가 유지되어 영역이 화면 안에 남고, `setPosition`으로 놓은 대화 상자는 왼쪽 위가 유지됩니다.

```js
const SIZE = 160;
const minimap = display.console.getControl(ConsoleControl.Minimap);

display.console.setMinimapSize(SIZE);
minimap.setAnchor(ConsoleAnchor.Right);
minimap.setPosition(640 - (10 + SIZE), 480 - (37 + SIZE));
```

미니맵이 어떤 인터페이스 너비에서도 오른쪽 아래 구석에 놓입니다.

### 5.6 데이터 파일

`game.map.resources.readText(name)`은 `resources/`의 아무 파일이나 UTF-8 텍스트로 읽고, `readJson(name)`은 JSON 파일을 파싱합니다.

```js
const heroes = game.map.resources.readJson('data/heroes.json');
```

`readJson`은 부를 때마다 새 값을 돌려줍니다. 공유 코드와 로컬 코드 모두에서 읽을 수 있습니다.

### 5.7 유닛 그래픽

- `image.setFrame(frame)`은 이미지 자신의 프레임이 바뀔 때까지 그 프레임을 그리고, `image.holdFrame(frame)`은 iscript가 계속 돌아도 `holdFrame(null)`까지 그 프레임을 유지합니다. 둘 다 그리는 것만 바꿉니다. 프레임에는 방향(`image.getDirection()`)이 포함되고, 이미지 그래픽에 그 프레임이 있어야 합니다.
- `unit.playAnimation(Animation.CastSpell)`은 명령처럼 iscript 애니메이션을 그 안의 opcode와 함께 실행합니다. 공격 애니메이션은 유닛의 대상을 공격합니다. 끊으면 안 되는 구간을 실행 중인 스프라이트는 애니메이션을 유지하고, 유닛의 명령은 상태가 바뀌면 자기 애니메이션을 다시 실행합니다. `Init`과 `Death`는 거부됩니다. 공유 코드 전용입니다.
- 스프라이트 타입의 선택 원, 그 세로 오프셋, 체력 바 너비를 바꿀 수 있습니다: `SpriteType.setSelectionCircle(size)`(0~9), `setSelectionCircleOffset(pixels)`, `setHealthBarWidth(pixels)`. 원은 보일 때, 바는 그릴 때 이 값을 씁니다. 공유 코드 전용입니다.

## 6. 맵 보호

```powershell
SC4J.bat tool scr-enhanced script pack Map.scx --script my-map -o Protected.scx --protect
```

`--protect`가 하는 일:

- 스크립트마다 주석과 불필요한 공백을 없애고, 함수와 블록 안에서 선언한 이름을 짧은 이름으로 바꿉니다. 스크립트는 원본과 똑같이 실행됩니다.
- 보호된 스크립트의 각 줄은 원본의 한 줄에서 나오므로 오류 줄을 되짚을 수 있습니다.
- 맵에 보호 표시를 하고, 그 맵으로 한 게임의 리플레이도 표시를 유지합니다. `unpack`은 보호된 맵을 거부합니다.
- 소스 맵 `Protected.scx.sourcemap.json`을 출력 파일 옆에 씁니다. 맵에는 들어가지 않습니다. 직접 보관하세요.

하지 않는 일:

- 암호화하지 않습니다. 이름으로 찾는 이름은 그대로입니다: 모듈 최상위 함수와 변수, 훅, `module.exports`, `exports`, `require` id, 속성 이름, API, 문자열 내용.
- `resources/`의 파일은 그대로 저장됩니다.

보호된 맵의 오류는 소스 맵으로 위치를 되짚습니다.

```powershell
SC4J.bat tool scr-enhanced script trace Protected.scx.sourcemap.json --at main.js:3
SC4J.bat tool scr-enhanced script trace Protected.scx.sourcemap.json --log sc4j.log
```

## 7. 제한

- 언어: Rhino 1.8.1에는 `class`, `async`/`await`, 전개 구문(`...`), `import`/`export`, 그리고 어떤 형태(`of`, `in`, 카운트 반복문)의 `for (const ...)`도 없습니다. `let`을 쓰세요. 반복문 본문에서 선언한 `const`는 첫 값을 유지하므로 `let`을 쓰세요. `for (let ...)` 반복문 본문에서 만든 함수들은 그 변수의 바인딩 하나를 공유하므로, 본문 안의 `let`에 변수를 복사하세요. `check`가 이것들을 알려 줍니다. target ES5로 컴파일한 TypeScript에는 이 제한이 없습니다(2.6절).
- Promise: `Promise`를 쓸 수 있습니다. 작업(`then`, `catch`, `finally`)은 그것을 대기열에 넣은 스크립트 호출(불러오기, 훅, 타이머, 채널 핸들러)이 끝날 때 같은 명령어 예산 안에서 실행됩니다. Promise는 시간을 기다리지 않으니 `game.after`를 쓰세요.
- 전역: `eval`, 문자열로 만드는 `Function`, `Date`, `setTimeout` 같은 타이머, Java 접근이 없습니다. `Math`는 모든 플랫폼에서 같은 결과를 냅니다.
- 명령어 예산: 스크립트 호출 하나(불러오기, 프레임, 결정, 로컬 입력, 로컬 프레임, 그리기)는 인터프리터 명령어 5,000,000개까지 실행할 수 있고, 함수 호출은 10,000단계까지 중첩됩니다. 긴 작업은 타이머로 여러 프레임에 나누세요.
- 스크립트 버튼: 게임마다 최대 256개.
- 채널 값: `null`, 불리언, 숫자, 문자열, 이것들로 된 배열과 일반 객체.
- 저장 게임: 스크립트가 있는 게임은 저장할 수 없습니다.
- 시작 유닛: 스타크래프트와 같이, 맵 설정 사용(Use Map Settings) 게임은 트리거가 있든 없든 맵에서 종족이 User Selectable인 플레이어에게 본진 건물과 일꾼 넷을 주고, 그 플레이어의 미리 놓인 유닛은 중립 유닛만 남깁니다. 맵에서 종족을 정해 두거나 `onStart`에서 유닛을 지우세요.
