package userUI


trait HotkeyObserver {
  def update(): Unit
}

object SharedSettingsModel {
  private var hotkeyAssignments: Map[String, Boolean] = Map()
  private var hotkeyToSpell: Map[String, String] = Map()  // Mapping of hotkeys to spell names
  private var observers: List[() => Unit] = List()

  def assignKey(key: String, spellName: String): Unit = {
    hotkeyAssignments += (key -> true)
    hotkeyToSpell += (key -> spellName)
    notifyObservers()
  }

  def unassignKey(key: String): Unit = {
    hotkeyAssignments -= key
    hotkeyToSpell -= key
    notifyObservers()
  }

  def isAssigned(key: String): Boolean = hotkeyAssignments.getOrElse(key, false)
  def getSpellNameForKey(key: String): Option[String] = hotkeyToSpell.get(key)

  private def notifyObservers(): Unit = {
    observers.foreach(_.apply())
  }

  def registerObserver(observer: () => Unit): Unit = {
    observers = observer :: observers
  }
}
