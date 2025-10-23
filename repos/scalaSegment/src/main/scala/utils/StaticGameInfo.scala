package utils

object StaticGameInfo {

  object Items {

    val SpearsIds: List[Int] = List(
      3277, // spear
    )

    val FoodsIds: List[Int] = List(
      3586, // orange
      3589, // coconuts
      3593, // melon
      3577, // meat
      3582, // ham
      3587, // banana
      3590, // cherry
      3725, // brown mushrooms
      3583, // dragon ham
      3578, // fish
      3607, //cheese
    )

    val healingRunes: List[Int] = List(
      3160, // UH
      3152, // IH
    )

    val healingFluids: List[(Int, Int)] = List(
      (2874, 7), // MF
      (2874, 10) // LF
    )

    val healingItems: List[Int] = healingRunes ++ healingFluids.map(_._1)

    val attackRunes: List[Int] = List(
      3198, // HMM
      3191, //  GFB
      3174, // LLM
      3155, // SD
      3200, // EXPLO
      3189, // FIREBALL


    )


  }


  object LevelMovementEnablers {
    val UpStairsIds: List[Int] = List(
      1947, // Stairs up
      1977, // stairs up
      1952, // Stairs up
      1958  // Stairs up
    )

    val UpRopesIds: List[Int] = List(
      386,
    )

    val UpLadderIds: List[Int] = List(
      1948, // Wooden ladder
    )

    val DownIds: List[Int] = List(
      369, // ladder down
      385, // Hole down
      412, // ladder down
      413, // Stairs down
      469, // stairs down
      414, // Stairs down
      428, // Stairs down
      432, // ladder down
      433, // ladder down
      434, // Stairs down
      437, // Stairs down
      469, // Stairs down
      594, // opened shovel hole
      413, // stairs
      411, // ladder
      437, // stairs
      438, // stairs
      1949, // teleport
      1950,
    )

    val DownGrateIds: List[Int] = List(
      435, // Grate down
    )


    val DownShovelHoleIds: List[Int] = List(
      593, // closed shovel hole
    )

    val AllUpIds: List[Int] = UpStairsIds ++ UpRopesIds ++ UpLadderIds
    val AllDownIds: List[Int] = DownIds ++ DownGrateIds
    val AllIds: List[Int] = AllUpIds ++ AllDownIds
    val leftClickMovement: List[Int] = DownIds ++ UpStairsIds

  }

}
