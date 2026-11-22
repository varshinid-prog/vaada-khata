package com.vaada.models

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The four supported merchant languages. [locale] is the BCP-47 code used for Gnani STT/TTS and sent to the voice
 * gateway; keep every locale string in this one place.
 */
enum class AppLanguage(   // ordinal order MUST match UiCatalog rows: en, hi, te, ta, kn

    val locale: String,
    val nativeName: String,
    val englishName: String,
    val greeting: String,
) {
    ENGLISH("en-IN", "English", "English", "Hello! Welcome to your shop."),
    HINDI("hi-IN", "हिन्दी", "Hindi", "नमस्ते! आपकी दुकान में आपका स्वागत है।"),
    TELUGU("te-IN", "తెలుగు", "Telugu", "నమస్కారం! మీ దుకాణానికి స్వాగతం."),
    TAMIL("ta-IN", "தமிழ்", "Tamil", "வணக்கம்! உங்கள் கடைக்கு வரவேற்கிறோம்."),
    KANNADA("kn-IN", "ಕನ್ನಡ", "Kannada", "ನಮಸ್ಕಾರ! ನಿಮ್ಮ ಅಂಗಡಿಗೆ ಸ್ವಾಗತ.");

    companion object {
        fun fromLocale(locale: String?): AppLanguage? = entries.firstOrNull { it.locale == locale }
    }
}

/** Translated UI text. Screens not yet migrated to this table still show their original (Hindi/English) text. */
data class UiStrings(
    val navAssistant: String, val navKhata: String, val navSettings: String,
    val idleHeadline: String, val processing: String, val replyReady: String,
    val stopped: String, val standby: String, val listening: String,
    val sending: String, val waitingReply: String, val youSaid: String, val yourShop: String,
    val language: String, val changeLanguage: String, val chooseLanguage: String, val continueLabel: String,
    val noSpeech: String, val micProblem: String,
) {
    companion object {
        fun of(l: AppLanguage): UiStrings = when (l) {
            AppLanguage.ENGLISH -> UiStrings(
                "Assistant", "Khata", "Settings",
                "Hello! What should I write?", "Understanding...", "Reply ready",
                "Assistant is off • start it with the button below", "Say \"Hey Jarvis\" or press the button and speak", "Listening... please speak",
                "Recorded • sending...", "Waiting for the reply...", "You said:", "Your shop",
                "Language", "Change language", "Choose your language", "Continue",
                "I did not hear anything. Press the mic and speak again.", "There is a problem with the microphone. Please try again.")
            AppLanguage.HINDI -> UiStrings(
                "सहायक", "खाता", "सेटिंग्स",
                "नमस्ते! मैं क्या लिखूँ?", "समझ रहा हूँ...", "जवाब तैयार है",
                "सहायक बंद है • नीचे दिए बटन से शुरू करें", "\"Hey Jarvis\" बोलें या बटन दबाकर कहें", "सुन रहा हूँ... अपनी बात बोलें",
                "रिकॉर्ड हो गया • भेज रहा है...", "जवाब मिल रहा है...", "आपने कहा:", "आपकी दुकान",
                "भाषा", "भाषा बदलें", "अपनी भाषा चुनें", "जारी रखें",
                "आवाज़ नहीं सुनाई दी। माइक दबाकर दोबारा बोलें।", "माइक्रोफ़ोन में समस्या है। दोबारा कोशिश करें।")
            AppLanguage.TELUGU -> UiStrings(
                "సహాయకుడు", "ఖాతా", "సెట్టింగ్‌లు",
                "నమస్కారం! ఏమి రాయాలి?", "అర్థం చేసుకుంటున్నాను...", "సమాధానం సిద్ధంగా ఉంది",
                "సహాయకుడు ఆపివేయబడింది • కింది బటన్‌తో ప్రారంభించండి", "\"Hey Jarvis\" అనండి లేదా బటన్ నొక్కి మాట్లాడండి", "వింటున్నాను... మాట్లాడండి",
                "రికార్డ్ అయింది • పంపుతున్నాను...", "సమాధానం వస్తోంది...", "మీరు చెప్పింది:", "మీ దుకాణం",
                "భాష", "భాష మార్చండి", "మీ భాషను ఎంచుకోండి", "కొనసాగించండి",
                "మాట వినిపించలేదు. మైక్ నొక్కి మళ్లీ చెప్పండి.", "మైక్రోఫోన్‌లో సమస్య ఉంది. మళ్లీ ప్రయత్నించండి.")
            AppLanguage.TAMIL -> UiStrings(
                "உதவியாளர்", "கணக்கு", "அமைப்புகள்",
                "வணக்கம்! என்ன எழுத வேண்டும்?", "புரிந்துகொள்கிறேன்...", "பதில் தயார்",
                "உதவியாளர் நிறுத்தப்பட்டுள்ளது • கீழே உள்ள பொத்தானால் தொடங்கவும்", "\"Hey Jarvis\" என்று சொல்லுங்கள் அல்லது பொத்தானை அழுத்திப் பேசுங்கள்", "கேட்கிறேன்... பேசுங்கள்",
                "பதிவானது • அனுப்புகிறேன்...", "பதில் வருகிறது...", "நீங்கள் சொன்னது:", "உங்கள் கடை",
                "மொழி", "மொழியை மாற்றுங்கள்", "உங்கள் மொழியைத் தேர்ந்தெடுக்கவும்", "தொடரவும்",
                "குரல் கேட்கவில்லை. மைக்கை அழுத்தி மீண்டும் பேசுங்கள்.", "மைக்ரோஃபோனில் சிக்கல். மீண்டும் முயற்சிக்கவும்.")
            AppLanguage.KANNADA -> UiStrings(
                "ಸಹಾಯಕ", "ಖಾತೆ", "ಸೆಟ್ಟಿಂಗ್‌ಗಳು",
                "ನಮಸ್ಕಾರ! ಏನು ಬರೆಯಲಿ?", "ಅರ್ಥಮಾಡಿಕೊಳ್ಳುತ್ತಿದ್ದೇನೆ...", "ಉತ್ತರ ಸಿದ್ಧವಾಗಿದೆ",
                "ಸಹಾಯಕ ನಿಲ್ಲಿಸಲಾಗಿದೆ • ಕೆಳಗಿನ ಬಟನ್‌ನಿಂದ ಪ್ರಾರಂಭಿಸಿ", "\"Hey Jarvis\" ಎನ್ನಿ ಅಥವಾ ಬಟನ್ ಒತ್ತಿ ಮಾತನಾಡಿ", "ಕೇಳುತ್ತಿದ್ದೇನೆ... ಮಾತನಾಡಿ",
                "ರೆಕಾರ್ಡ್ ಆಗಿದೆ • ಕಳುಹಿಸುತ್ತಿದ್ದೇನೆ...", "ಉತ್ತರ ಬರುತ್ತಿದೆ...", "ನೀವು ಹೇಳಿದ್ದು:", "ನಿಮ್ಮ ಅಂಗಡಿ",
                "ಭಾಷೆ", "ಭಾಷೆ ಬದಲಿಸಿ", "ನಿಮ್ಮ ಭಾಷೆಯನ್ನು ಆಯ್ಕೆಮಾಡಿ", "ಮುಂದುವರಿಸಿ",
                "ಧ್ವನಿ ಕೇಳಿಸಲಿಲ್ಲ. ಮೈಕ್ ಒತ್ತಿ ಮತ್ತೆ ಮಾತನಾಡಿ.", "ಮೈಕ್ರೊಫೋನ್‌ನಲ್ಲಿ ಸಮಸ್ಯೆ ಇದೆ. ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ.")
        }
    }
}

val LocalUiStrings = staticCompositionLocalOf { UiStrings.of(AppLanguage.ENGLISH) }
