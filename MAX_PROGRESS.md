# MAX — прогресс работ

Ветка `claude/grambas-repo-exploration-iproqy`. Модуль `TMessagesProj_Max`
(в `patches/fork-changes.patch` не входит, авто-апдейтом восстанавливается целиком).

## 2026-11-19

### Сделано

1. **Шапка чата — стекло как в Telegram.**
   - `MaxGlass.kt`: разделены фабрики — `colorFactory` для списков (вкладка MAX,
     белый фон) и `chatFactory` на `BlurredBackgroundSourceWrapped` +
     `WallpaperBitmapProvider` + `ViewPositionWatcher` для чата.
   - `MaxGlass.attach/updateWallpaper/detach`: источник размытых обоев
     подключается перед созданием плашек, размер обоев обновляется по layout.
   - `MaxChatFragment.kt`: обои (`ChatBackground`) создаются первыми,
     `MaxGlass.attach` до плашек, `MaxGlass.detach` в `onFragmentDestroy`.

2. **Анимированный градиент исходящих пузырей — как в Telegram.**
   - Причина «статичного» градиента: в Telegram цвет пузырей не течёт
     постоянно, а переезжает на следующую позицию при каждой отправке
     (`ChatActivity.rotateMotionBackgroundDrawable`), а лента перерисовывается
     подписчиком `NotificationCenter.invalidateMotionBackground`. В MAX не было
     ни ротации, ни перерисовки, поэтому все пузыри висели на нулевой фазе.
   - `MaxBubbles.rotateOutGradient()`: ротация общего исходящего
     `MotionBackgroundDrawable` (`switchToNextPosition`), только если тема
     анимированная.
   - `MaxBubbles.Bubble.draw`: пока идёт переезд (`posAnimationProgress < 1`),
     пузырь сам вызывает `invalidateSelf()`; иначе кадры генерируются, но вид
     не перерисовывается и переход замирает.
   - `MaxChatFragment.merge()`: ротация на каждом новом своём сообщении
     (`isOutgoing && id < 0`).


3. **v2.4.22 — волна и расшифровка.** Собрано и выпущено (run 44841511368).
   По замечанию пользователя расшифровка теперь по кнопке «Т», а не
   автоматически: `MaxVoice.toggle` (развернуть/свернуть, повтор при ошибке),
   кнопка в строке голосового, текст показывается только в развёрнутом виде.

### Проверено

- Заглушки `compile-check/gen` дополнены (`MotionBackgroundDrawable`,
  `getMotionBackgroundDrawable`, `getPosAnimationProgress`, `chat_msgOutDrawable`,
  `invalidateViews`); `gradle -p TMessagesProj_Max/compile-check compileKotlin`
  — BUILD SUCCESSFUL.
- Сборка APK `v2.4.20` собрана через `build.yml` (workflow_dispatch, run
  44533728217, success): prerelease
  https://github.com/0siriss/telegram_gx/releases/tag/v2.4.20, APK 68.7 МБ.

### Осталось

- Проверить на устройстве: шапка чата со стеклом, пузыри плавно перекрашиваются
  при отправке каждого сообщения.
- Закоммитить в `patches/fork-changes.patch` ничего не нужно — правки только
  в MAX-модуле.


## 2026-11-21

### Голосовые как в Komet

1. **Осциллограмма и встроенная расшифровка.**
   - `MaxModels.kt`: у `MaxAttach` появились `audioId` и `wave` (80 байт 0..120);
     `parseWave` понимает массив, строку кодов и обёртку `{"$bin": base64}`.
   - `MaxClient.kt`: опкод 202 `requestTranscription` (`mediaId` = `audioId`) и
     push 293 `PUSH_TRANSCRIPTION_RESULT`.
   - `MaxVoice.kt`: кэш расшифровок по `messageId`, ленивый запрос при показе
     пузыря, повтор при статусе 0, `acceptPush` для push 293.
   - `MaxVoiceWaveView.kt`: столбики из `wave`, прогресс по позиции
     `MediaPlayer`, тап по волне — перемотка.
   - `MaxChatFragment.kt`: отдельная строка голосового (кнопка, волна,
     длительность) и `transcript` под ней; `MaxVoice.onChanged` перерисовывает
     список.

2. **v2.4.21 (шапка/градиент).**
   - Установленный APK совпал по SHA-256 с релизным — проблема не в доставке
     сборки. Зафиксировано, что ротация вызывается при отправке.
   - Гипотезы: `SharedConfig.chatBlurEnabled()` = false (непрозрачная шапка)
     и/или `Theme.getColor(key_chat_outBubbleGradientAnimated) == 0` в теме
     пользователя.

### Проверено

- `compile-check/gen` + `gradle -p TMessagesProj_Max/compile-check compileKotlin`
  — BUILD SUCCESSFUL.

### Осталось

- Проверить на устройстве волну и расшифровку, затем собрать очередной APK
  через workflow_dispatch.

## Запреты

- Скриншоты с телефона не пушить в репозиторий (см. `CLAUDE.md`).
