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

### Проверено

- Заглушки `compile-check/gen` дополнены (`MotionBackgroundDrawable`,
  `getMotionBackgroundDrawable`, `getPosAnimationProgress`, `chat_msgOutDrawable`,
  `invalidateViews`); `gradle -p TMessagesProj_Max/compile-check compileKotlin`
  — BUILD SUCCESSFUL.
- Сборка APK `v2.4.20` запущена через `build.yml` (workflow_dispatch).

### Осталось

- Проверить на устройстве: шапка чата со стеклом, пузыри плавно перекрашиваются
  при отправке каждого сообщения.
- Закоммитить в `patches/fork-changes.patch` ничего не нужно — правки только
  в MAX-модуле.

## Запреты

- Скриншоты с телефона не пушить в репозиторий (см. `CLAUDE.md`).
