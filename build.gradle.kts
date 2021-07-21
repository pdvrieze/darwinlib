/*
 * Copyright (c) 2018.
 *
 * This file is part of ProcessManager.
 *
 * ProcessManager is free software: you can redistribute it and/or modify it under the terms of version 3 of the
 * GNU Lesser General Public License as published by the Free Software Foundation.
 *
 * ProcessManager is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with ProcessManager.  If not,
 * see <http://www.gnu.org/licenses/>.
 */

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-android-extensions")
}

version = "1.1.99"
description = "Android support library for darwin functionality"

//group = 'android'

ext {
    if(!rootProject.hasProperty("androidTarget")) {
        set("androidTarget", "28")
    }
    if (!rootProject.hasProperty("androidCompatVersion")) {
        set("androidCompatVersion", "28.0.0")
    }
    if (!has("kotlin_version")) {
        set("kotlin_version", "1.3.40")
    }
}

val androidCompatVersion: String by project
val kotlin_version: String by project
val androidTarget: String by project

android {
    compileSdkVersion(androidTarget.toInt())

    defaultConfig {
        minSdkVersion(14)
        targetSdkVersion(androidTarget.toInt())
        versionCode=2
        versionName=version.toString()
    }

    compileOptions {
        sourceCompatibility=JavaVersion.VERSION_1_8
        targetCompatibility=JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("com.android.support:appcompat-v7:${androidCompatVersion}")
    implementation("com.android.support:support-compat:${androidCompatVersion}")
    implementation("com.android.support.constraint:constraint-layout:1.1.3")
    api("net.devrieze:android-coroutines-appcompat:0.7.991")
    api("net.devrieze:android-coroutines:0.7.991")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.2.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.2.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlin_version")
}

repositories {
    mavenLocal()
    mavenCentral()
    google()
    maven("https://plugins.gradle.org/m2/")
}

