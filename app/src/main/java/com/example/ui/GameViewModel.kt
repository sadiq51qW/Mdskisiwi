package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.GameDatabase
import com.example.data.GameRepository
import com.example.model.JournalEntry
import com.example.model.LifeEvent
import com.example.model.PlayerProfile
import com.example.model.SavedGame
import com.example.model.StateChanges
import com.example.network.GenerateContentRequest
import com.example.network.GenerationConfig
import com.example.network.Content as GeminiContent
import com.example.network.Part as GeminiPart
import com.example.network.RetrofitClient
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface GameState {
    object Welcome : GameState
    object CreateCharacter : GameState
    object GeneratingScene : GameState
    object Playing : GameState
    data class GameOver(val finalResults: String, val isWin: Boolean) : GameState
}

class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: GameRepository
    val savedGames: StateFlow<List<SavedGame>>

    init {
        val database = GameDatabase.getDatabase(application)
        repository = GameRepository(database.savedGameDao())
        savedGames = repository.allSaves.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    // Game state tracking
    private val _gameState = MutableStateFlow<GameState>(GameState.Welcome)
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _playerProfile = MutableStateFlow<PlayerProfile?>(null)
    val playerProfile: StateFlow<PlayerProfile?> = _playerProfile.asStateFlow()

    private val _currentEvent = MutableStateFlow<LifeEvent?>(null)
    val currentEvent: StateFlow<LifeEvent?> = _currentEvent.asStateFlow()

    private val _journal = MutableStateFlow<List<JournalEntry>>(emptyList())
    val journal: StateFlow<List<JournalEntry>> = _journal.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Moshi adapters for JSON conversions
    private val playerProfileAdapter: JsonAdapter<PlayerProfile> by lazy {
        RetrofitClient.moshiInstance.adapter(PlayerProfile::class.java)
    }

    private val lifeEventAdapter: JsonAdapter<LifeEvent> by lazy {
        RetrofitClient.moshiInstance.adapter(LifeEvent::class.java)
    }

    private val journalListAdapter: JsonAdapter<List<JournalEntry>> by lazy {
        val type = Types.newParameterizedType(List::class.java, JournalEntry::class.java)
        RetrofitClient.moshiInstance.adapter(type)
    }

    // Verify API Key
    fun isApiKeyConfigured(): Boolean {
        // AI Studio automatically injects actual API key. If empty or equal to placeholder, we are in Demo mode.
        val key = com.example.BuildConfig.GEMINI_API_KEY
        return key.isNotEmpty() && key != "MY_GEMINI_API_KEY"
    }

    fun startCharacterCreation() {
        _gameState.value = GameState.CreateCharacter
        _errorMessage.value = null
    }

    fun exitToWelcome() {
        _gameState.value = GameState.Welcome
        _playerProfile.value = null
        _currentEvent.value = null
        _journal.value = emptyList()
        _errorMessage.value = null
    }

    // Start a new game with the created profile
    fun startGame(profile: PlayerProfile) {
        _playerProfile.value = profile
        _journal.value = emptyList()
        _gameState.value = GameState.GeneratingScene
        _errorMessage.value = null

        viewModelScope.launch {
            generateFirstEvent(profile)
        }
    }

    // Submit player decision (Option 1, 2, 3 or Custom decision text)
    fun makeDecision(decisionText: String) {
        val currentProfile = _playerProfile.value ?: return
        val currentScene = _currentEvent.value ?: return

        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                // Compile past history for memory
                val historyPrompt = _journal.value.joinToString("\n") {
                    "الدور ${it.turn}: الحدث: ${it.eventDesc} -> الخيار المتخذ: ${it.decision} -> النتيجة: ${it.resultDesc}"
                }

                val prompt = """
                    رائع، اللاعب اتخذ قراره التالي: "$decisionText"
                    الحدث الذي كان يمر به حالاً: "${currentScene.eventDescription}"
                    شخصيات كانت متواجدة: "${currentScene.keyCharacters}"
                    فرص ومخاطر سابقة كادرها: "${currentScene.opportunitiesAndRisks}"
                    
                    الحالة الحالية للشخصية:
                    الاسم: ${currentProfile.name}
                    الجنس: ${currentProfile.gender}
                    البلد: ${currentProfile.country}
                    المدينة: ${currentProfile.city}
                    التعليم: ${currentProfile.education}
                    المهنة الحالية: ${currentProfile.profession}
                    الثروة الحالية: ${currentProfile.wealth} 💰
                    أهداف الحياة: ${currentProfile.goal}
                    السن الحالي: ${currentProfile.age} سنة
                    الصحة الحالية: ${currentProfile.health} ❤️
                    السعادة الحالية: ${currentProfile.happiness} 😊
                    مؤشر العلاقات: ${currentProfile.relations} 👥
                    عدد الأدوار السابقة: ${currentProfile.turnCount}
                    
                    سجل التاريخ والقرارات السابقة لتذكيرك:
                    $historyPrompt

                    المهمة:
                    بناءً على القرار المختار، صف السيناريو والتطورات التالية بأسلوب تفصيلي ومثير.
                    يجب أن تحلل القرار منطقياً وتعطيه عواقب (إيجابية أو سلبية أو كليهما) بذكاء وواقعية شديدة.
                    إذا كان القرار مستحيلاً أو غريباً، نفذه بأسلوب يحمل عواقب منطقية ساخرة أو صعبة دون رفضه.
                    حدث حالة الشخصية (العمر، الثروة، الصحة، السعادة، العلاقات، والمهنة) بالزيادة أو النقصان في حقل 'stateChanges' بشكل متناسب منطقياً. مثلاً، إذا سافر قلل الثروة؛ إذا تعرض لحادث قلل الصحة؛ إذا نجح تجارياً زد الثروة والسعادة؛ إلخ.
                    تنتهي اللعبة بالوفاة إذا وصلت الصحة إلى 0 أو أقل؛ أو بالنصر إذا حقق الهدف الرئيسي له وأردت تحجيم القصة كخاتمة عظيمة.
                    
                    في حال الوفاة أو النصر الإجمالي: اجعل مصفوفة الخيارات فارغة []، واكتب النتيجة النهائية للخاتمة بنص مفصل جداً.
                    
                    أرجع النتيجة بصيغة JSON حصرية، لا تلفها بعلامات الاقتباس البرمجية أو نصوص خارجية:
                    {
                      "eventDescription": "سرد تفصيلي لمجريات الحدث الجديد وعاقبة القرار المتخذ...",
                      "keyCharacters": "تفاصيل الشخصيات الموجودة ورأيهم في اللاعب حالياً وعلاقتهم به...",
                      "opportunitiesAndRisks": "الفرص والتهديدات الجديدة الناتجة...",
                      "options": ["خيار ١", "خيار ٢", "خيار ٣"],
                      "stateChanges": {
                        "ageChange": 1, // كم سنة مرت في هذا التطور (عادة 0 أو 1 أو أكثر)
                        "wealthChange": -200,
                        "healthChange": -5,
                        "happinessChange": 10,
                        "relationsChange": 2,
                        "professionChange": null // أو اكتب المسمى الجديد لو تغيرت الوظيفة
                      }
                    }
                """.trimIndent()

                val nextEvent = if (isApiKeyConfigured()) {
                    callGeminiModel(prompt)
                } else {
                    simulateNextEvent(currentProfile, decisionText, currentScene)
                }

                if (nextEvent != null) {
                    // Update stats logically with boundary limits (0 - 100)
                    val change = nextEvent.stateChanges
                    val newAge = currentProfile.age + change.ageChange
                    val newWealth = Math.max(0, currentProfile.wealth + change.wealthChange)
                    val newHealth = Math.max(0, Math.min(100, currentProfile.health + change.healthChange))
                    val newHappiness = Math.max(0, Math.min(100, currentProfile.happiness + change.happinessChange))
                    val newRelations = Math.max(0, Math.min(100, currentProfile.relations + change.relationsChange))
                    val newProfession = if (!change.professionChange.isNullOrBlank()) change.professionChange else currentProfile.profession

                    val updatedProfile = currentProfile.copy(
                        age = newAge,
                        wealth = newWealth,
                        health = newHealth,
                        happiness = newHappiness,
                        relations = newRelations,
                        profession = newProfession,
                        turnCount = currentProfile.turnCount + 1,
                        isDead = newHealth <= 0,
                        isWin = nextEvent.options.isEmpty() && newHealth > 0
                    )

                    // Add to journal history
                    val entry = JournalEntry(
                        turn = currentProfile.turnCount + 1,
                        eventDesc = currentScene.eventDescription,
                        decision = decisionText,
                        resultDesc = nextEvent.eventDescription
                    )
                    _journal.value = _journal.value + entry

                    _playerProfile.value = updatedProfile
                    _currentEvent.value = nextEvent

                    // Check Game Over transitions
                    if (updatedProfile.isDead) {
                        _gameState.value = GameState.GameOver(
                            finalResults = "لقد توفيت... ${nextEvent.eventDescription}\n\nالإحصائيات النهائية: سن الوفاة: ${updatedProfile.age} سنة، الثروة: ${updatedProfile.wealth}💰، العلاقات: ${updatedProfile.relations}👥.",
                            isWin = false
                        )
                    } else if (updatedProfile.isWin) {
                        _gameState.value = GameState.GameOver(
                            finalResults = "تهانينا! لقد حققت هدفك بنجاح باهر! \n\n${nextEvent.eventDescription}\n\nنهاية القصة حافلة بالإنجازات والعطاء لمستقبل أفضل.",
                            isWin = true
                        )
                    }
                } else {
                    _errorMessage.value = "حدث خطأ أثناء معالجة القرار مع خادم الذكاء الاصطناعي."
                }
            } catch (e: Exception) {
                Log.e("GameViewModel", "Error in decision processing", e)
                _errorMessage.value = "خطأ: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun generateFirstEvent(profile: PlayerProfile) {
        _isLoading.value = true
        try {
            val prompt = """
                أنت محرك لعبة تفاعلية نصية احترافي ومتقدم ("لعبة الحياة التفاعلية"). مهمتك هي توليد قصة حياة ديناميكية تتبع شخصية اللاعب وتتجاوب مع قراراته.
                هذا هو دور البداية الأول. صمم حدث افتتاحياً مشوقاً للشخصية بناءً على معطياتها الشخصية والهدف الذي تحلم به.
                
                معلومات الشخصية:
                الاسم: ${profile.name}
                السن الحالي: ${profile.age} سنة
                الجنس: ${profile.gender}
                البلد: ${profile.country}
                المدينة: ${profile.city}
                التعليم: ${profile.education}
                المهنة الحالية: ${profile.profession}
                الثروة الابتدائية: ${profile.wealth} 💰
                هدف الحياة الشخصي: ${profile.goal}
                
                التوجيهات:
                ١. صمم هذا الحدث الافتتاحي بأسلوب ممتع وتفصيلي (الزمان، المكان، الخصائص المحيطة، الحلفاء والخصوم المبدئيين).
                ٢. اطرح ٣ خيارات ذكية ومتنوعة للبداية ومثيرة للاهتمام للغاية.
                ٣. أرجع النتيجة على شكل JSON مطابق للتصميم تماماً بدون أي علامات ترميزية خارجية:
                {
                  "eventDescription": "مقدمة تفصيلية للحدث الأول والوضع الراهن بلغة عربية سردية بديعة ودرامية...",
                  "keyCharacters": "تفاصيل الشخصيات المحيطة المتواجدة حالاً وأول لقاء لك معهم...",
                  "opportunitiesAndRisks": "الفرص الفورية والمخاطر التي تلوح في الأفق في مطلع قصتك...",
                  "options": [
                    "الخيار الأول",
                    "الخيار الثاني",
                    "الخيار الثالث"
                  ],
                  "stateChanges": {
                    "ageChange": 0,
                    "wealthChange": 0,
                    "healthChange": 0,
                    "happinessChange": 0,
                    "relationsChange": 0,
                    "professionChange": null
                  }
                }
            """.trimIndent()

            val responseEvent = if (isApiKeyConfigured()) {
                callGeminiModel(prompt)
            } else {
                simulateFirstEvent(profile)
            }

            if (responseEvent != null) {
                _currentEvent.value = responseEvent
                _gameState.value = GameState.Playing
            } else {
                _errorMessage.value = "فشل في إنشاء مغامرتك الأولى. يرجى مراجعة إعدادات مفتاح API."
                _gameState.value = GameState.Welcome
            }
        } catch (e: Exception) {
            Log.e("GameViewModel", "First event failed", e)
            _errorMessage.value = "حدث خطأ: ${e.localizedMessage}"
            _gameState.value = GameState.Welcome
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun callGeminiModel(promptPrompt: String): LifeEvent? = withContext(Dispatchers.IO) {
        val apiKey = com.example.BuildConfig.GEMINI_API_KEY
        val request = GenerateContentRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(GeminiPart(text = promptPrompt))
                )
            ),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                temperature = 0.85f
            )
        )

        try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (jsonText != null) {
                val cleanedJson = cleanJsonString(jsonText)
                Log.d("GameViewModel", "Gemini response: $cleanedJson")
                lifeEventAdapter.fromJson(cleanedJson)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("GameViewModel", "API connection error", e)
            null
        }
    }

    private fun cleanJsonString(input: String): String {
        var cleaned = input.trim()
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substringAfter("```json")
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substringAfter("```")
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substringBeforeLast("```")
        }
        return cleaned.trim()
    }

    // Save current game state to Slot
    fun saveCurrentSlot() {
        val profile = _playerProfile.value ?: return
        val currentScene = _currentEvent.value ?: return
        val currentJournal = _journal.value

        viewModelScope.launch {
            try {
                val saveObject = SavedGame(
                    characterName = profile.name,
                    profession = profile.profession,
                    wealth = profile.wealth,
                    health = profile.health,
                    happiness = profile.happiness,
                    lastActiveTurn = profile.turnCount,
                    playerProfileJson = playerProfileAdapter.toJson(profile),
                    journalJson = journalListAdapter.toJson(currentJournal),
                    currentEventJson = lifeEventAdapter.toJson(currentScene)
                )
                repository.saveGame(saveObject)
                Log.d("GameViewModel", "Game saved successfully!")
            } catch (e: Exception) {
                Log.e("GameViewModel", "Saving failed", e)
            }
        }
    }

    // Load Game slot
    fun loadGameSlot(savedGame: SavedGame) {
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val profile = playerProfileAdapter.fromJson(savedGame.playerProfileJson)
                val currentScene = lifeEventAdapter.fromJson(savedGame.currentEventJson)
                val journalList = journalListAdapter.fromJson(savedGame.journalJson) ?: emptyList()

                if (profile != null && currentScene != null) {
                    _playerProfile.value = profile
                    _currentEvent.value = currentScene
                    _journal.value = journalList
                    
                    if (profile.isDead) {
                        _gameState.value = GameState.GameOver(
                            finalResults = "لقد توفيت... \n\nالإحصائيات النهائية: سن الوفاة: ${profile.age} سنة، الثروة: ${profile.wealth}💰، العلاقات: ${profile.relations}👥.",
                            isWin = false
                        )
                    } else if (profile.isWin) {
                        _gameState.value = GameState.GameOver(
                            finalResults = "تهانينا! لقد حققت هدفك بنجاح! \n\n${currentScene.eventDescription}",
                            isWin = true
                        )
                    } else {
                        _gameState.value = GameState.Playing
                    }
                } else {
                    _errorMessage.value = "خطأ في قراءة ملف الحفظ المختار."
                }
            } catch (e: Exception) {
                Log.e("GameViewModel", "Loading failed", e)
                _errorMessage.value = "فشل التحميل: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Delete game slot
    fun deleteGameSlot(id: Int) {
        viewModelScope.launch {
            repository.deleteGame(id)
        }
    }

    // SIMULATION STAND-BY MODE (When API Key is missing or invalid)
    private fun simulateFirstEvent(profile: PlayerProfile): LifeEvent {
        return LifeEvent(
            eventDescription = "أهلاً بك يا ${profile.name} في ${profile.country}. تشرق شمس الصباح على شوارع مدينة ${profile.city} المزدحمة. تقف اليوم في مقتبل العمر (${profile.age} سنة) حاملاً غايتك الأسمى: '${profile.goal}'. كـ '${profile.profession}'، بحوزتك حقيبة صغيرة وجزء بسيط من المال يبلغ ${profile.wealth} 💰 لتبدأ به مستقبلك المشرق. تقابل في طريقك رجلاً عجوزاً يبتسم لك ويدير معك حديثاً سريعاً.",
            keyCharacters = "أبو محمد (جارك العجوز، يبتسم لك دائماً ويحب مساعدتك ورأيه بك جيد جداً)",
            opportunitiesAndRisks = "الفرص: يمكنك قبول نصيحة أبو محمد الاستثمارية. المخاطر: قد تضيع بعضاً من مالك المحدود إذا تهورت في الشراء أو السفر الاستعجالي.",
            options = listOf(
                "اسأل أبو محمد عن أفضل الفرص المتاحة في السوق للبدء ومكافحة التحديات.",
                "اشكر أبو محمد وحاول البحث عن فرصة عمل سريعة ومستقلة لتأمين قوت يومك في المدينة.",
                "قرر المغامرة بكل مالك ${profile.wealth} 💰 في تذكرة سفر عاجلة نحو العاصمة لبدء مشروع أحلامك هناك."
            ),
            stateChanges = StateChanges(ageChange = 0, wealthChange = 0, healthChange = 0, happinessChange = 0, relationsChange = 1)
        )
    }

    private fun simulateNextEvent(profile: PlayerProfile, decision: String, prev: LifeEvent): LifeEvent {
        val rand = (1..3).random()
        return when {
            decision.contains("أبو محمد") || prev.eventDescription.contains("محمد") -> {
                LifeEvent(
                    eventDescription = "استمعت لنصائح العجوز الحكيم بكل اهتمام. أرشدك نحو متجر تجاري متواضع يرغب صاحبه في الاستعانة بمهني نشيط مثلك. ذهبت وقابلته وتم قبولك على الفور براتب ثابت يغطي أساسياتك، وشعرت بسعادة غامرة بهذا الدعم الطيب في مستهل رحلتك العظيمة.",
                    keyCharacters = "أحمد السعيد (صاحب المتجر الجديد، رجل معتدل وعادل، ممتن لإنتاجيتك).",
                    opportunitiesAndRisks = "الفرص: فرصة لتعلم أسرار التجارة الحرة والترقي. المخاطر: ساعات العمل الشاقة قد تكلفك بعض الإرهاق الجسدي وصحتك.",
                    options = listOf(
                        "اعمل بجد ليل نهار لطلب زيادة العائد المالي وتأمين مستقبلك بشكل أسرع.",
                        "ركّز على تكوين علاقات قوية مع الزبائن والعملاء وبناء شبكة علاقات شخصية ممتازة.",
                        "اقترح على صاحب المتجر تمويل شراكة استثمارية لتوسيع النشاط مستغلاً ذكائك المهني."
                    ),
                    stateChanges = StateChanges(ageChange = 1, wealthChange = 400, healthChange = -2, happinessChange = 10, relationsChange = 5)
                )
            }
            decision.contains("العاصمة") || decision.contains("سفر") || decision.contains("تذكرة") -> {
                LifeEvent(
                    eventDescription = "لقد حزمت أمتعتك وغادرت نحو العاصمة الكبيرة ذات الأنوار البراقة والمنافسة الشرسة. بمجرد وصولك، واجهتك صعوبة السكن وتضاعف أسعار المعيشة مما استنزف قسماً كبيراً من أموالك، لكنك تقف الآن ممتلئاً بالطاقة والعزيمة للبحث عن حلمك وسط فرص العاصمة الذهببية.",
                    keyCharacters = "سمير اللامع (وسيط عقاري يبحث لك عن سكن متواضع، شخص ذكي وحذر للغاية ورأيه محايد بك).",
                    opportunitiesAndRisks = "الفرص: صفقات تجارية مفتوحة وخبرة عالية. المخاطر: الإفلاس السريع في حال عدم تأمين مصدر دخل فوري في المدينة.",
                    options = listOf(
                        "اقبل بأرخص سكن مشترك ريثما تلتحق بوظيفة تناسب شهادتك التعليمية ${profile.education}.",
                        "ابدأ مشروعاً تجارياً متناهي الصغر على رصيف الأسواق المزدحمة لبيع بضائع بسيطة بمالك المتبقي.",
                        "تقدم فوراً للعمل في مطاعم العاصمة الفاخرة للعمل الليلي براتب سخي لإنهاء ضائقتك المالية."
                    ),
                    stateChanges = StateChanges(ageChange = 1, wealthChange = -300, healthChange = -5, happinessChange = 5, relationsChange = 2)
                )
            }
            else -> {
                LifeEvent(
                    eventDescription = "أمضيت في دربك بخطوات واثقة، واكتسبت بعض الخبرات الفردية الرائعة في شوارع المدينة. عملت كفريلانسر مكافح وجمعت مبالغ جيدة لتعزيز مركزك المالي، وتطورت قدرتك الجسدية على البقاء والتحدي رغم الصعوبات والوحدة.",
                    keyCharacters = "وليد (زميل عمل عابر، مرح ونشط، ينصحك بإنشاء حساب بنكي عاجل لتأمين مالك).",
                    opportunitiesAndRisks = "الفرص: اكتشاف عمل تقني بمرونة عالية للإنتاج العالي. المخاطر: العزلة الطويلة قد تسبب لك كآبة وتدهوراً بسيطاً في علاقاتك العامة.",
                    options = listOf(
                        "خصص بعض الوقت لدراسة فرص الاستثمار الرقمي عبر الإنترنت لتنمية ممتلكاتك الشحيحة.",
                        "اخرج للترويح عن نفسك والبحث عن زمالة وصداقات حقيقية في نوادي المدينة لتعديل معنوياتك.",
                        "ضاعف نشاطك وكرّس وقتك بالكامل للابتكار وتقديم خدماتك للأعمال المتنامية بكفاءة عالية."
                    ),
                    stateChanges = StateChanges(
                        ageChange = 1,
                        wealthChange = 500,
                        healthChange = if (profile.health < 20) 10 else -2,
                        happinessChange = 5,
                        relationsChange = -3
                    )
                )
            }
        }
    }
}
