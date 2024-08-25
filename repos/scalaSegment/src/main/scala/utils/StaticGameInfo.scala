package utils

object StaticGameInfo {

  object LevelMovementEnablers {
    val UpStairsIds: List[Int] = List(
      1947, // Stairs up
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
      413, // Stairs down
      414, // Ladder down
      428, // Stairs down
      434, // Stairs down
      469, // Stairs down
      594, // opened shovel hole
      413, //stairs
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
    val AllDownIds: List[Int] = DownIds ++ DownGrateIds ++ DownShovelHoleIds
    val AllIds: List[Int] = AllUpIds ++ AllDownIds
    val leftClickMovement: List[Int] = DownIds ++ UpStairsIds

  }

}
