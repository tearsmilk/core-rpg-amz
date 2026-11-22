# spacephys — общая физика космоса

Чистая Java-библиотека (`--release 21`, без зависимостей, без Minecraft и Minestom). Сервер (модуль `gravity`,
Java 25) и клиентский мини-модуль (`spaceflight`, Fabric, Java 21) считают по ней одинаково — один источник истины.
Это не модуль сервера: в `run/module/` не попадает, а **вшивается** в jar модулей, которым нужна (класслоадеры
модулей изолированы).

## Единицы

1 блок = 1 м, тик `Units.TICK` = 0,05 с. Библиотека везде считает **в секундах**: скорость — блоки/с,
ускорение — блоки/с², μ = G·M — блоки³/с². Minestom (`Entity#setVelocity`) — тоже блоки/с. Vanilla-клиент хранит
скорость в блоках/тик: `Units.toPerTick(v)` (÷20), `Units.accelToPerTick(a)` (÷400) и обратные.

μ тела задаётся одним из трёх способов: `Units.muFromSurfaceGravity(g, R)` = g·R²,
`Units.muFromOrbit(r, T)` = 4π²r³/T², или напрямую. Опорные числа: μ = 1371 → круговая орбита r = 50 с периодом
60 с, v_circ = 5,24 б/с, v_esc = 7,4 б/с.

## Классы

| Класс | Что делает |
|---|---|
| `Vec3d`, `Quat` | Вектор и кватернион ориентации (соглашение MC: взгляд +Z, верх +Y, право −X; `Quat.fromYawPitchRoll` даёт тот же forward, что `getLookAngle`) |
| `Body` | Тело: `PLANET`/`STAR` (снаружи 1/r², внутри однородный шар), `SMALL` (Пламмер, смягчение ε), `HOLE` (Пачинский–Виита, горизонт r_s, ISCO ≈ 3r_s). `soi` — сфера влияния с плавным затуханием (только ради скорости, у малых тел) |
| `Field` | Поле карты — сумма по всем телам без отсечки по reach. `acceleration`, `potential`, `tidal` (Δa ≈ 2μL/r³), `dominant` (для SAS/HUD), `horizonAt`. Неизменяемо, потокобезопасно; при изменении тел — новый `Field.of(...)` |
| `State` | Изменяемые позиция и скорость (открытые поля, без аллокаций в тике) |
| `Integrator` | `step` — KDK с подшагами n = ceil(dt/(η·√(r³/μ))) ≤ 16; по умолчанию схема Ёсиды 4-го порядка (`DEFAULT`), простой Verlet — `VERLET`. `kick` / `stepEuler` — симплектический Эйлер, как у клиента. Захват дырой проверяется отрезком — горизонт не «прошивается»; метод возвращает дыру (позиция — на горизонте) или `null` |
| `Thrust` | Ранец 6DoF в базисе ориентации: 2 б/с², запас Δv 60 б/с (`Spec.unlimited` — без ограничения), `refill`, SAS (`stabilize`) тоже тратит Δv |
| `Orbit` | Элементы орбиты (перицентр, апоцентр, e, период, наклон), опорные скорости, прогноз траектории для HUD |
| `Contact` | Удар о блок: отскок e = 0,2, трение только в момент контакта, скорость удара для урона (порог 8 б/с) |

Ни сопротивления, ни «ветра» в библиотеке нет: без тяги и тел скорость сохраняется точно.

## Пример

```java
Field field = Field.of(
        Body.planetFromSurfaceGravity("terra", 0, 64, 0, 9.8, 40),
        Body.hole("bh", 500, 64, 0, 2000, 4));
State s = new State(x, y, z, vx, vy, vz);            // блоки, блоки/с
Thrust pack = new Thrust();                          // на игрока: хранит остаток Δv
Vec3d a = pack.control(fwd, strafe, up, orientation, sas, s.velocity(), Units.TICK);

// сервер (предметы, прогноз): KDK с подшагами
Body hole = Integrator.DEFAULT.step(field, s, Units.TICK, a);
if (hole != null) { /* захват: смерть или спавн — решает модуль по конфигу */ }

// клиент: только скорость, сдвиг делает vanilla move с коллизиями
Integrator.DEFAULT.kick(field, s, Units.TICK, a.x(), a.y(), a.z());
player.setDeltaMovement(Units.toPerTick(s.vx), Units.toPerTick(s.vy), Units.toPerTick(s.vz));
```

## Детерминизм

В горячем пути только `+ − × ÷` и `Math.sqrt` (корректно округляется по IEEE 754); для `pow`/`sin`/`cos`/`cbrt`
вне тика — `StrictMath`. С Java 17 арифметика strictfp по умолчанию, поэтому одинаковые входы дают побитово
одинаковый результат на JDK 21 и 25, с JIT и без (проверено). Не добавляйте `Math.pow`, `Math.fma` и прочие
интринсики в формулы поля и интегратора.

## Как вшить

Сервер (в этой сборке), например в `gravity/build.gradle.kts`:

```kotlin
evaluationDependsOn(":spacephys")
dependencies { "implementation"(project(":spacephys")) }
tasks.jar { from(project(":spacephys").the<SourceSetContainer>()["main"].output) }   // классы прямо в gravity.jar
```

Клиентский мини-модуль (отдельная сборка `client-modules/`): исходники подключаются напрямую, тогда классы
сами окажутся в jar модуля:

```kotlin
sourceSets.main { java.srcDir("../../spacephys/src/main/java") }   // путь — от каталога модуля
```

При вшивании в несколько модулей одного процесса конфликтов нет: у каждого модуля свой класслоадер.

## Сборка и тесты

```bash
cd hoka-odyssey
./gradlew :spacephys:test
```

Тесты: `FieldTest` (законы поля, суперпозиция, a = −∇φ, сфера влияния, приливы), `IntegratorTest` (круговая
орбита замыкается < 0,5 % за 100 оборотов; энергия и момент на 10⁵ тиков; без тел скорость сохраняется точно;
падение в дыру не прошивает горизонт; побитовый детерминизм), `OrbitTest`, `ThrustTest`, `QuatContactTest`.
