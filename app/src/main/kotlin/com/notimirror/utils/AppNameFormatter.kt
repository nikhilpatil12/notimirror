package com.notimirror.utils

/**
 * Formats iOS app bundle identifiers into user-friendly app names
 */
object AppNameFormatter {

    fun format(appIdentifier: String): String {
        if (appIdentifier.isBlank()) return "Unknown"

        // Handle common iOS app bundle IDs
        return when (appIdentifier) {
            "com.apple.MobileSMS" -> "Messages"
            "com.apple.mobilephone" -> "Phone"
            "com.apple.mobilemail" -> "Mail"
            "com.apple.mobilecal" -> "Calendar"
            "com.apple.reminders" -> "Reminders"
            "com.apple.Health" -> "Health"
            "com.apple.facetime" -> "FaceTime"
            "com.apple.MobileNotes" -> "Notes"
            "com.apple.news" -> "News"
            "com.apple.stocks" -> "Stocks"
            "com.apple.weather" -> "Weather"
            "com.apple.Fitness" -> "Fitness"
            "com.apple.Preferences" -> "Settings"
            "com.apple.Maps" -> "Maps"
            "com.apple.Music" -> "Music"
            "com.apple.TV" -> "TV"
            "com.apple.Podcasts" -> "Podcasts"
            "com.apple.wallet" -> "Wallet"
            "com.apple.Home" -> "Home"
            "com.apple.findmy" -> "Find My"
            "com.apple.Shortcuts" -> "Shortcuts"
            "com.facebook.Facebook" -> "Facebook"
            "com.facebook.Messenger" -> "Messenger"
            "com.atebits.Tweetie2" -> "X (Twitter)"
            "com.burbn.instagram" -> "Instagram"
            "net.whatsapp.WhatsApp" -> "WhatsApp"
            "com.toyopagroup.picaboo" -> "Snapchat"
            "com.google.Gmail" -> "Gmail"
            "com.google.Maps" -> "Google Maps"
            "com.google.chrome.ios" -> "Chrome"
            "com.google.GoogleMobile" -> "Google"
            "com.google.Drive" -> "Google Drive"
            "com.google.Photos" -> "Google Photos"
            "com.google.Meet" -> "Google Meet"
            "com.google.Docs" -> "Google Docs"
            "com.spotify.client" -> "Spotify"
            "com.netflix.Netflix" -> "Netflix"
            "tv.twitch" -> "Twitch"
            "com.reddit.Reddit" -> "Reddit"
            "com.discord.Discord" -> "Discord"
            "com.ubercab.UberClient" -> "Uber"
            "com.ubereats.UberEats" -> "Uber Eats"
            "com.microsoft.Office.Outlook" -> "Outlook"
            "com.microsoft.skype.teams" -> "Teams"
            "com.microsoft.Office.Word" -> "Word"
            "com.microsoft.Office.Excel" -> "Excel"
            "com.microsoft.Office.Powerpoint" -> "PowerPoint"
            "com.microsoft.onenote" -> "OneNote"
            "com.zhiliaoapp.musically" -> "TikTok"
            "com.linkedin.LinkedIn" -> "LinkedIn"
            "com.slack.Slack" -> "Slack"
            "com.coinbase.Coinbase" -> "Coinbase"
            "com.amazon.Amazon" -> "Amazon"
            "com.airbnb.app" -> "Airbnb"
            "com.pinterest.Pinterest" -> "Pinterest"
            "com.skype.skype" -> "Skype"
            "com.viber.Viber" -> "Viber"
            "ph.telegra.Telegraph" -> "Telegram"
            "com.paypal.PPClient" -> "PayPal"
            "com.venmo" -> "Venmo"
            "com.squareup.cash" -> "Cash App"
            "com.robinhood.release.Robinhood" -> "Robinhood"
            "com.github.stormhub" -> "GitHub"
            "com.dropbox.Dropbox" -> "Dropbox"
            "com.notion.Notion" -> "Notion"
            "com.figma.Figma" -> "Figma"
            "com.adobe.PSMobile" -> "Photoshop"
            "com.adobe.lrmobile" -> "Lightroom"
            "com.canva.Canva" -> "Canva"
            "com.shazam.Shazam" -> "Shazam"
            "com.soundcloud.TouchApp" -> "SoundCloud"
            "com.pandora.PandoraMusic" -> "Pandora"
            "com.tinder.Tinder" -> "Tinder"
            "com.bumble.Bumble" -> "Bumble"
            "com.hinge.Hinge" -> "Hinge"
            "com.duolingo.DuolingoMobile" -> "Duolingo"
            "com.strava.stravaride" -> "Strava"
            "com.nike.nikeplus" -> "Nike"
            "com.adidas.runtastic" -> "Adidas Running"
            "com.myfitnesspal.mfp" -> "MyFitnessPal"
            else -> {
                // Extract last component and format it nicely
                appIdentifier.substringAfterLast(".")
                    .replace("-", " ")
                    .replace("_", " ")
                    .split(" ")
                    .joinToString(" ") { word ->
                        word.replaceFirstChar { it.uppercase() }
                    }
            }
        }
    }
}