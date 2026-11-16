# Ядро MAX вызывается через JNI, и часть классов существует только для нативной стороны.
#
# Главное: ru.kolibri.KolibriException из Kotlin-кода никто не создаёт, его бросает
# только Rust через FindClass("ru/kolibri/KolibriException"). R8 считал класс
# неиспользуемым и выбрасывал его из релизной сборки, поэтому вместо текста ошибки
# ядра на экран приходил NoClassDefFoundError с именем самого класса.
-keep class ru.kolibri.** { *; }
-keepclassmembers class ru.kolibri.** { *; }
-dontwarn ru.kolibri.**
