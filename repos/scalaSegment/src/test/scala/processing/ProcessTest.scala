//package processing
//package processing
//
//import org.scalatest.flatspec.AnyFlatSpec
//import org.scalatest.matchers.should.Matchers
//import play.api.libs.json.Json
//
//class ProcessTest extends AnyFlatSpec with Matchers {
//
//  "findItemInContainerSlot14" should "find the correct item in the specified container slots" in {
//    // Mock JSON input simulating the structure expected by the function
//    val jsonInput = Json.parse(
//      """
//        |{
//        |  "containersInfo": {
//        |    "container1": {
//        |      "items": {
//        |        "slot1": {"itemId": 123, "itemSubType": 1},
//        |        "slot2": {"itemId": 456, "itemSubType": 2}
//        |      }
//        |    }
//        |  },
//        |  "screenInfo": {
//        |    "inventoryPanelLoc": {
//        |      "container1": {
//        |        "contentsPanel": {
//        |          "slot1": {"x": 100, "y": 200},
//        |          "slot2": {"x": 150, "y": 250}
//        |        }
//        |      }
//        |    }
//        |  }
//        |}
//        |""".stripMargin)
//
//    // Expected output for the given input
//    val expectedOutput = Json.obj("x" -> 100, "y" -> 200)
//
//    // Call the function under test
//    val result = Process.findItemInContainerSlot14(jsonInput, 123, 1)
//
//    // Assertion
//    result shouldBe Some(expectedOutput)
//  }
//}
