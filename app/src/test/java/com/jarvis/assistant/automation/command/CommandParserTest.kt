package com.jarvis.assistant.automation.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    @Test fun recognizesPlayStoreInstallAsSearchNotSilentInstall() {
        val parsed = CommandParser.parse("download Instagram from Play Store")
        assertTrue(parsed.command is JarvisCommand.InstallApp)
        assertEquals("instagram", (parsed.command as JarvisCommand.InstallApp).appName)
    }

    @Test fun recognizesHinglishCallAndPhoneNumberCalls() {
        assertTrue(CommandParser.parse("Amit ko call lagao").command is JarvisCommand.PhoneCall)
        assertEquals("9876543210", (CommandParser.parse("call 9876543210").command as JarvisCommand.PhoneCall).contactOrNumber)
    }

    @Test fun recognizesOrderedMultiStepCommands() {
        val parsed = CommandParser.parse("turn on torch then call Amit")
        assertTrue(parsed.command is JarvisCommand.Sequence)
        assertEquals(2, (parsed.command as JarvisCommand.Sequence).commands.size)
    }

    @Test fun recognizesLiveLocationSharingIntent() {
        val parsed = CommandParser.parse("share my live location with Amit on WhatsApp")
        assertTrue(parsed.command is JarvisCommand.ShareLocation)
        assertTrue((parsed.command as JarvisCommand.ShareLocation).live)
    }
}
