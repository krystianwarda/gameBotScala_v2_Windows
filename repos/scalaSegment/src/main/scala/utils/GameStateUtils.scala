package utils

import play.api.libs.json.{JsObject, JsValue}
import processing.CaveBotFeature.{Vec, WaypointInfo}
import utils.SettingsUtils.UISettings

import scala.collection.mutable



case class GameState(
                      general: GeneralState = GeneralState(),
                      characterInfo: CharacterInfoState = CharacterInfoState(),
                      autoLoot: AutoLootState = AutoLootState(),
                      caveBot: CaveBotState = CaveBotState(),
                      autoTarget: AutoTargetState = AutoTargetState(),
                      autoHeal: AutoHealState = AutoHealState(),
                      guardian: GuardianState = GuardianState(),
                      fishing: FishingState = FishingState(),
                      jsonProcessing: JsonProcessingState = JsonProcessingState()
                    )

case class JsonProcessingState(
                                isProcessing: Boolean = false,
                                processingStartTime: Long = 0L,
                                processingTimeout: Long = 5000L // 5 seconds timeout
                              )

case class CharacterInfoState(
                         presentCharLocation: Vec = Vec(0, 0),
                         presentCharZLocation: Int = 0,
                         lastDirection: Option[String] = None,
                       )

case class CaveBotState(
                         stateHunting: String = "free",
                         antiOverpassDelay: Long = 0,
                         slowWalkStatus: Int = 0,
                         waypointsLoaded: Boolean = false,
                         currentWaypointIndex: Int = 0,
                         subWaypoints: List[Vec] = List(),
                         fixedWaypoints: List[WaypointInfo] = List(),
                         caveBotLevelsList: List[Int] = List(),
                         antiCaveBotStuckStatus: Int = 0,
                         currentWaypointLocation: Vec = Vec(0, 0),
                         gridBoundsState: (Int, Int, Int, Int) = (0, 0, 0, 0),
                         gridState: Array[Array[Boolean]] = Array.ofDim[Boolean](10, 10),
                       )

case class AutoHealState(
                          lastHealUseTime: Long = 0,
                          stateHealingWithRune: String = "free",
                          dangerLevelHealing: Boolean = false,
                          statusOfAutoheal:String = "",

                          healingRuneContainerName:String = "",
                          healingMPContainerName:String = "",
                          healingHPContainerName:String = "",

                          healingCrosshairActive: Boolean = false,
                          mpBoost: Boolean = false,
                          healingRestryStatus: Int = 0,
                          healingRetryAttempts: Int = 1,
                          healingSpellCooldown: Long = 1200,
                          lightHeal: LightHealState = LightHealState(),
                          strongHeal: StrongHealState = StrongHealState(),

                          healUseRandomness: Long = 0,
                          healingUseCooldown: Long = 1000,
                          runeUseCooldown: Long = 2000,
                          runeUseRandomness: Long = 0,
                          runeUseTimeRange: (Int, Int) = (500, 1000)
                        )


case class GeneralState(
                         initialSettingsSet: Boolean = false,
                         areInitialContainerSet: Boolean = false,
                         initialContainersList: List[String] = List(),
                         retryCounters: Map[String, Int] = Map.empty,
                         timestamps: Map[String, Long] = Map.empty,
                         flags: Map[String, Boolean] = Map.empty,
                         lastMousePos: Option[(Int, Int)] = None,
                         lastActionCommand: Option[String] = None,
                         lastActionTimestamp: Option[Long] = None,
                         temporaryData: Map[String, String] = Map.empty,
                         retryThroughoutFishesStatus: Option[Int] = None,
                         retryAttempts:  Int = 0,
                         retryAttemptsVerLong: Int = 7,

//                         retryAttemptsLong: Option[Long] = None
                       )



case class FishingState(
                         retryThroughoutFishesStatus: Option[Int] = None,
                         retryAttemptsLong: Option[Long] = None,
                         lastFishingCommandSent: Long = 0,
                         retryFishingStatus: Int = 0,
                         fishingRetryAttempts: Int = 4,
                         retryMergeFishStatus: Int = 0,
                         retryMidDelay: Int = 1000
                       )

case class LightHealState(
                           lightHealDelayTime: Long = 0,
                           lightHealDelayTimeRange: (Int, Int) = (3000, 6000),
                           lowHealUseTimeRange: (Int, Int) = (800, 1500),
                         )

case class StrongHealState(
                            strongHealUseTimeRange: (Int, Int) = (200, 500),
                            strongHealDelayTimeRange: (Int, Int) = (1500, 3000),
                          )



case class AutoLootState(
                          stateLooting: String = "free",
                          stateLootPlunder: String = "free",
                          carsassToLoot: List[(String, Long)] = List(),
                          lastAutoLootActionTime: Long = 0,
                          startTimeOfLooting: Long = 0,
                          unfreezeAutoLootCooldown: Long = 15000L,
                          throttleCoefficient: Double = 1.3,
                          lastContainerContent: String = "",
                          autoLootActionThrottle: Long = 600L,
//                          lastItemActionCommandSend: Long = 0,
                          carcassTileToLoot: Option[(String, Long)] = None,
                          lastLootedCarcassTile: Option[(String, Long)] = None,
                          carcassToLootImmediately: List[(String, Long, String)] = List(),
                          carcassToLootAfterFight: List[(String, Long, String)] = List(),

                          lootIdToPlunder: Int = 0,
                          lootCountToPlunder: Int = 0,
                          lootScreenPosToPlunder: Vec = Vec(0, 0),
                          dropScreenPosToPlunder: Vec = Vec(0, 0),

                          // Add container state tracking
                          lastItemIdAndCountEngaged: (Int, Int) = (0, 0),

                          lastEatFoodTime: Long = 0,
                          subWaypoints: List[Vec] = List(),
                          gridBoundsState: (Int, Int, Int, Int) = (0, 0, 0, 0), // Example default value
                          gridState: Array[Array[Boolean]] = Array.ofDim[Boolean](10, 10),
                          currentWaypointLocation: Vec = Vec(0, 0),
                          lastDirection: Option[String] = None,
                        )


case class Creature(
                     name: String,
                     count: Int,
                     danger: Int,
                     targetBattle: Boolean,
                     loot: Boolean,
                     chase: Boolean,
                     keepDistance: Boolean,
                     avoidWaves: Boolean,
                     useRune: Boolean,
                     useRuneOnScreen: Boolean,
                     useRuneOnBattle: Boolean
                   )

case class AutoTargetState(
                            updateAttackChangeTime: Long = 0,
                            updateAttackThrottleTime: Long = 4000L,
                            lastTargetActionTime: Long = 0,
                            plannedMarkingMode: Option[String] = None,
                            targetActionThrottle: Long = 600L,

                            creaturePositionHistory: Map[Int, List[(Vec, Long)]] = Map.empty,
                            chaseMode: String = "chase_to", // "chase_to" or "chase_after"
//                            lastCreatureMovementCheck: Long = 0,
                            creatureMovementVector: Option[Vec] = None,


                            randomMovementThrottle: Long = RandomUtils.randomBetween(1500, 5000),
                            lastRandomMovementTime: Long = 0,
                            isActivelyMoving: Boolean = false,
                            lastKnownCreaturePosition: Option[Vec] = None,

                            dangerLevelHealing: String = "low",
                            lastMarkingAttemptedId: Int = 0,
                            lastTargetLookoutTime: Long = 0,
                            lastRuneUseTime: Long = 0,
                            dangerCreaturesList: Seq[Creature] = Seq.empty,
                            creatureTarget: Int = 0,
                            lastTargetName: String = "",
                            lastTargetPos: (Int, Int, Int) = (0,0,0),
                            stateAutoTarget: String = "not_set",
                            stateMarkingTarget: String = "free",
                            autoTargetContainerMapping: Map[Int, String] = Map.empty[Int, String],
                            currentAutoAttackContainerName: String = "",
                            isUsingAmmo: String = "not_set",
                            ammoId: Int = 0,
                            ammoCountForNextResupply: Int = 0,
                            AttackSuppliesLeftMap: Map[Int, Int] = Map.empty[Int, Int],
                            chosenTargetId: Int = 0,
                            chosenTargetName: String = "",
                            lastTargetMarkCommandSend: Long = 0,
                            targetFreezeCreatureId: Int = 0,
                            subWaypoints: List[Vec] = List(),
                            runeUseCooldown: Long = 2000,
                            runeUseRandomness: Long = 0,
                            runeUseTimeRange:  (Int, Int) = (500,1000),
                            stopReason: Option[String] = None,
                            targetCreatureToAttack: Option[String] = None
                          )

case class GuardianState(
                          playerDetectedAlertTime: Long = 0,
                          lastGuardianAction: Long = 0,
                        )


case class ProcessorState(
                           initialSettingsSet: Boolean = false,
                           autoloot: AutoLootState = AutoLootState(),
                           cavebot: CaveBotState = CaveBotState(),
                           autotarget: AutoTargetState = AutoTargetState(),
                           autoheal: AutoHealState = AutoHealState(),
                           guardian: GuardianState = GuardianState(),

                           gmDetected: Boolean = false,
                           gmDetectedTime: Long = 0,
                           GMlastDialogueTime: Long = 0,

                           gmWaitTime: Long = 45000,
                           messageRespondRequested: Boolean = false,
                           messageListenerTime: Long = 0,
                           preparedAnswer: String = "",
                           dialogueHistory: mutable.Buffer[(JsValue, String)] = mutable.Buffer.empty,
                           respondedMessages: mutable.Set[String] = mutable.Set.empty,
                           pendingMessages: mutable.Queue[(JsValue, Long)] = mutable.Queue.empty,
                           characterLastRotationTime: Long = 0,

                           // chat reader
                           chatReaderStatus: String = "not_ready",
                           chatDesiredTab: String = "",
                           chatAction: String = "",
                           chatDesiredTabsList: List[String] = List(),
                           inParty: Boolean = false,
                           lastChatReaderAction: Long = 0,
                           dialogueHistoryPartyTab: mutable.Buffer[(JsValue, String, String)] = mutable.Buffer.empty,

                           lastEmailAlertTime: Long = 0,
                           stateHealingWithRune: String = "free",
                           healingCrosshairActive: Boolean = false,
                           healingRestryStatus: Int = 0,
                           healingRetryAttempts: Int = 1,
                           healingSpellCooldown: Long = 1200,

                           currentTime: Long = 0,
                           chasingBlockerLevelChangeTime: Long = 0,
                           shortTimeLimit: Long = 1000,
                           normalTimeLimit: Long = 2000,
                           longTimeLimit: Long = 5000,
                           delayTimeLimit: Long = 10000,

                           lastExtraWindowLoot: Long = 0,
                           extraWidowLootStatus: Int = 0,
                           fixedWaypoints: List[WaypointInfo] = List(),
                           currentWaypointLocation: Vec = Vec(0, 0),
                           lastHealingTime: Long = 0,
                           lastSpellCastTime: Long = 0,
                           lastRuneMakingTime: Long = 0,
                           lastRuneUseTime: Long = 0,
                           runeUseCooldown: Long = 2000,
                           suppliesContainerToHandle: String = "",
                           suppliesLeftMap: Map[Int, Int] = Map.empty[Int, Int],
                           suppliesContainerMap: Map[Int, String] = Map.empty[Int, String],
                           healingUseCooldown: Long = 1000,
                           lastHealUseTime: Long = 0,
                           healUseRandomness: Long = 0,
                           highHealUseTimeRange:  (Int, Int) = (200,500),
                           highHealDelayTimeRange: (Int, Int) = (1500,3000),
                           lowHealUseTimeRange:  (Int, Int) = (800,1500),
                           lowHealDelayTimeRange:  (Int, Int) = (3000,6000),
                           lowHealDelayTime: Long = 0,

                           runeUseRandomness: Long = 0,
                           runeUseTimeRange:  (Int, Int) = (500,1000),
                           lastMoveTime: Long = 0,
                           lastTrainingCommandSend: Long = 0,
                           lastProtectionZoneCommandSend: Long = 0,
                           settings: Option[UISettings],
                           lastAutoResponderCommandSend: Long = 0,
                           lastCaveBotCommandSend: Long = 0,
                           lastTeamHuntCommandSend: Long = 0,
                           currentWaypointIndex: Int = 0,
                           currentTargetIndex: Int = 0,
                           subWaypoints: List[Vec] = List(),
                           waypointsLoaded: Boolean = false, // Added list of subway point
                           lastDirection: Option[String] = None,
                           lastAutoTargetCommandSend: Long = 0,

                           chosenTargetId: Int = 0,
                           chosenTargetName: String = "",
                           lastChosenTargetPos: (Int, Int, Int) = (0,0,0),
                           attackPlayers: Boolean = false,
                           lastTargetMarkCommandSend: Long = 0,

                           creatureTarget: Int = 0,
                           lastTargetName: String = "",
                           lastTargetPos: (Int, Int, Int) = (0,0,0),
                           lastBlockerPos: Vec = Vec(0, 0),
                           lastBlockerPosZ: Int = 0,

                           positionStagnantCount: Int = 0,
                           lastPosition: Option[Vec] = None,

                           //                           attackRuneContainerName: String = "not_set",
                           statusOfAttackRune: String = "not_set",
                           lastChangeOfstatusOfAttackRune: Long = 0,
                           uhRuneContainerName: String = "not_set",
                           statusOfRuneAutoheal: String = "not_ready",


                           stateTargeting: String = "free",




                           caveBotLevelsList: List[Int] = List(),
                           antiOverpassDelay: Long = 0,
                           monstersListToLoot: List[String] = List(),

                           staticContainersList: List[String] = List(),
                           gridState: Array[Array[Boolean]] = Array.ofDim[Boolean](10, 10), // Example default value
                           gridBoundsState: (Int, Int, Int, Int) = (0, 0, 0, 0), // Example default value
                           presentCharLocation: Vec = Vec(0, 0),
                           presentCharZLocation: Int = 0,


                           // ammo resuply
                           isUsingAmmo: String = "not_set",
                           ammoId: Int = 0,
                           ammoCountForNextResupply: Int = 0,
                           ammoResuplyDelay: Long = 0,

                           alreadyLootedIds: List[Int] = List(),
                           retryStatus: Int = 0,
                           slowWalkStatus: Int = 0,
                           antiCaveBotStuckStatus: Int = 0,
                           chaseSwitchStatus: Int = 0,
                           lootingStatus: Int = 0,
                           lootingRestryStatus: Int = 0,


                           retryFishingStatus: Int = 0,
                           retryMergeFishStatus: Int = 0,
                           retryMergeFishDelay: Long = 0,
                           retryThroughoutFishesStatus: Int = 0,


                           retryAttempts: Int = 4,
                           retryAttemptsShort: Int = 15,
                           retryAttemptsMid: Int = 23,
                           retryMidDelay: Long = 2000,
                           retryShortDelay: Long = 1000,
                           retryAttemptsLong: Int = 30,
                           retryAttemptsVerLong: Int = 60,
                           targetFreezeStatus: Int = 0,
                           targetFreezeHealthStatus: Int = 0,
                           targetFreezeHealthPoints: Int = 0,
                           targetFreezeCreatureId: Int = 0,
                           escapedToSafeZone: String = "not_set",
                         )