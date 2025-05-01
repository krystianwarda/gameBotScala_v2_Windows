package utils

object StaticGameInfo {

  object Items {
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
      413, // Stairs down
      469, // stairs down
      414, // Stairs down
      428, // Stairs down
      432, // ladder down
      434, // Stairs down
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
    val AllDownIds: List[Int] = DownIds ++ DownGrateIds ++ DownShovelHoleIds
    val AllIds: List[Int] = AllUpIds ++ AllDownIds
    val leftClickMovement: List[Int] = DownIds ++ UpStairsIds

  }

}
