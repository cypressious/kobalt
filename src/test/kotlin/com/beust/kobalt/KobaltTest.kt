package com.beust.kobalt

import com.beust.kobalt.api.Kobalt
import org.testng.annotations.BeforeSuite
import org.testng.annotations.Guice

@Guice(modules = arrayOf(TestModule::class))
open class KobaltTest {
    @BeforeSuite
    public fun bs() {
        Kobalt.INJECTOR = com.google.inject.Guice.createInjector(TestModule())
    }
}