package player

class Player (val characterName: String) {

  // character values
  var charExperience: Int = 0
  var charLevel: Int = 0
  var healthPoints: Int = 0
  var manaPoints: Int = 0
  var soulPoints: Int = 0
  var capacityValue = 0
  var magicLevel: Int = 0
  var lastMealTimestamp: Long = System.currentTimeMillis / 1000
  var lastSpellTimestamp: Long = System.currentTimeMillis
  var lastCharacterRotation: Long = System.currentTimeMillis() / 100


  // bot settings values
  var threadActivation: Boolean = true
  // private var inputHandler: Option[InputHandler] = None
  var autoHealPanelClass: String = ""
  var botLightHealSpell: String = ""
  var botLightHealHealth: Int = 0
  var botLightHealMana: Int = 0
  var botStrongHealSpell: String = ""
  var botStrongHealHealth: Int = 0
  var botStrongHealMana: Int = 0
  var botIhHealHealth: Int = 0
  var botIhHealMana: Int = 0
  var botUhHealHealth: Int = 0
  var botUhHealMana: Int = 0
  var botHPotionHealHealth: Int = 0
  var botHPotionHealMana: Int = 0
  var botMPotionHealManaMin: Int = 0
  var botMPotionHealManaMax: Int = 0

  var autoHealEnabled: Boolean = false
  var runeMakerEnabled: Boolean = false

  def updateAutoHeal(lightSpell: String, lightHealth: Int, lightMana: Int,
                     strongSpell: String, strongHealth: Int, strongMana: Int,
                     ihHealth: Int, ihMana: Int,
                     uhHealth: Int, uhMana: Int,
                     hPotionHealth: Int, hPotionMana: Int,
                     mPotionManaMin: Int, mPotionManaMax: Int): Unit = {
    botLightHealSpell = lightSpell
    botLightHealHealth = lightHealth
    botLightHealMana = lightMana
    botStrongHealSpell = strongSpell
    botStrongHealHealth = strongHealth
    botStrongHealMana = strongMana
    botIhHealHealth = ihHealth
    botIhHealMana = ihMana
    botUhHealHealth = uhHealth
    botUhHealMana = uhMana
    botHPotionHealHealth = hPotionHealth
    botHPotionHealMana = hPotionMana
    botMPotionHealManaMin = mPotionManaMin
    botMPotionHealManaMax = mPotionManaMax
  }

}




