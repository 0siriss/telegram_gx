// Быстрая проверка компиляции Kotlin-кода модуля MAX без Android SDK: настоящие классы
// Android из Robolectric android-all, вместо Telegram — заглушки, которые gen/ генерирует
// из исходников TMessagesProj с точными сигнатурами. Запуск: ./check.sh
plugins { kotlin("jvm") version "2.1.0" }
repositories { mavenCentral() }
dependencies {
    compileOnly("org.robolectric:android-all:14-robolectric-10818077")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) }
}
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
sourceSets {
    main {
        kotlin.srcDirs("../src/main/kotlin", "../kolibri/kolibri-kotlin/library/src/main/kotlin")
        java.srcDirs("build/stubs")
    }
}
