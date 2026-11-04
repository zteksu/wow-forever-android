-- OctoPad: WoW Forever's Gamepad (Alpha) controls for the 1.12 client.
--
-- The app sends every controller button as its own key (see KEYS); this file turns those presses into
-- Forever's scheme: LT/RT layers over the D-pad and face buttons, bumpers that target on a tap and hold
-- a targeting modifier, LB+RB shortcuts, a radial menu on Start and D-pad navigation of open menus.
-- The sticks never reach the addon: the app turns them into camera and movement.

OctoPad = {}
local OP = OctoPad
OP.version = "1.0"

-- Keys the app's "Forever Gamepad" controller profile sends for each button.
OP.KEYS = {
  A = "F1", B = "F2", X = "F3", Y = "F4",
  UP = "F5", RIGHT = "F6", DOWN = "F7", LEFT = "F8",
  LB = "F9", RB = "F10", LT = "F11", RT = "F12",
  L3 = "NUMPADDIVIDE", R3 = "NUMPADMULTIPLY", START = "NUMPADMINUS",
}

-- The eight action buttons, in action-slot order within a layer.
OP.ACTION_BUTTONS = { "A", "B", "X", "Y", "UP", "RIGHT", "DOWN", "LEFT" }
OP.FACE = { A = true, B = true, X = true, Y = true }
OP.DPAD = { UP = true, RIGHT = true, DOWN = true, LEFT = true }
-- Face buttons sit where the D-pad buttons do on the other side of the pad.
OP.MIRROR = { UP = "Y", RIGHT = "B", DOWN = "A", LEFT = "X", Y = "UP", B = "RIGHT", A = "DOWN", X = "LEFT" }

OP.GLYPH = "Interface\\AddOns\\OctoPad\\img\\"
OP.TAP_TIME = 0.35
OP.HOLD_TIME = 0.5

local DEFAULTS = {
  compact = false,
  fixedParty = false,
  swapModifierSides = false,
  swapFriendlyActions = false,
  swapHostileActions = false,
  autoTarget = true,
  hideBlizzardBars = true,
  interactPrompt = true,
  cameraSpeed = 1.0,
  scale = 1.0,
}

BINDING_HEADER_OCTOPAD = "OctoPad (controller)"
BINDING_NAME_OCTOPAD_A = "A"
BINDING_NAME_OCTOPAD_B = "B"
BINDING_NAME_OCTOPAD_X = "X"
BINDING_NAME_OCTOPAD_Y = "Y"
BINDING_NAME_OCTOPAD_UP = "D-pad Up"
BINDING_NAME_OCTOPAD_RIGHT = "D-pad Right"
BINDING_NAME_OCTOPAD_DOWN = "D-pad Down"
BINDING_NAME_OCTOPAD_LEFT = "D-pad Left"
BINDING_NAME_OCTOPAD_LB = "Left Bumper"
BINDING_NAME_OCTOPAD_RB = "Right Bumper"
BINDING_NAME_OCTOPAD_LT = "Left Trigger"
BINDING_NAME_OCTOPAD_RT = "Right Trigger"
BINDING_NAME_OCTOPAD_L3 = "Left Stick"
BINDING_NAME_OCTOPAD_R3 = "Right Stick"
BINDING_NAME_OCTOPAD_START = "Start"

-- held[button] = press time; used[button] = another button was pressed while it was held.
OP.held = {}
OP.used = {}
OP.handlers = {}

function OP:Print(msg)
  DEFAULT_CHAT_FRAME:AddMessage("|cff33ccffOctoPad|r: " .. msg)
end

function OP:Setting(name)
  local value = OctoPadDB and OctoPadDB[name]
  if value == nil then return DEFAULTS[name] end
  return value
end

function OP:Set(name, value)
  OctoPadDB[name] = value
  self:Fire("SETTING", name)
end

-- A tiny callback list so the other files can react to state changes.
function OP:On(event, fn)
  if not self.handlers[event] then self.handlers[event] = {} end
  table.insert(self.handlers[event], fn)
end

function OP:Fire(event, a, b)
  local list = self.handlers[event]
  if not list then return end
  for i = 1, table.getn(list) do list[i](a, b) end
end

-- ---- Key bindings ----

local applyingBindings = false

local function bound(key)
  if GetBindingAction then return GetBindingAction(key) end
end

function OP:ApplyBindings()
  if applyingBindings then return end
  applyingBindings = true
  for button, key in pairs(self.KEYS) do
    if bound(key) ~= "OCTOPAD_" .. button then SetBinding(key, "OCTOPAD_" .. button) end
  end
  -- The app's left stick presses these: it only moves, the right stick turns.
  local movement = { W = "MOVEFORWARD", S = "MOVEBACKWARD", Q = "STRAFELEFT", E = "STRAFERIGHT" }
  for key, action in pairs(movement) do
    if bound(key) ~= action then SetBinding(key, action) end
  end
  applyingBindings = false
end

-- ---- Layers ----

-- "BASE", "LT", "RT" or "LTRT", from the triggers held right now.
function OP:Layer()
  local lt, rt = self.held.LT, self.held.RT
  if lt and rt then return "LTRT" end
  if lt then return "LT" end
  if rt then return "RT" end
  return "BASE"
end

function OP:Arrangement()
  return OctoPadCharDB.arrangement or 1
end

function OP:SetArrangement(n)
  if n < 1 then n = 3 elseif n > 3 then n = 1 end
  OctoPadCharDB.arrangement = n
  self:Fire("LAYOUT")
end

-- The two targeting modifiers: friendly on LB and hostile on RB, unless swapped.
function OP:TargetModifiers()
  if self:Setting("swapModifierSides") then return "RB", "LB" end
  return "LB", "RB"
end

-- ---- Input ----

-- Something that takes over the buttons while it is open (radial, flyout, menus, bind mode). Each has
-- Active() and Press(button); Release(button) and Trigger(button) are optional.
OP.captures = {}

-- Lower priority numbers win: bind mode, radial, flyout, then menu navigation.
function OP:AddCapture(capture, priority)
  capture.priority = priority
  table.insert(self.captures, capture)
  table.sort(self.captures, function(a, b) return a.priority < b.priority end)
end

function OP:ActiveCapture()
  for i = 1, table.getn(self.captures) do
    local c = self.captures[i]
    if c:Active() then return c end
  end
end

function OctoPad_Button(button, keystate)
  if keystate == "down" then OP:Press(button) else OP:Release(button) end
end

function OP:Press(button)
  -- Key repeat sends more "down"s for a held key.
  if self.held[button] then return end
  for other in pairs(self.held) do self.used[other] = true end
  self.held[button] = GetTime()
  self.used[button] = nil

  local ok, err = pcall(self.Dispatch, self, button)
  if not ok then self:Print("|cffff4040" .. tostring(err)) end
  self:Fire("INPUT")
end

function OP:Release(button)
  local pressed = self.held[button]
  if not pressed then return end
  self.held[button] = nil
  local used = self.used[button]
  self.used[button] = nil
  local tap = not used and GetTime() - pressed < self.TAP_TIME

  local ok, err = pcall(self.DispatchRelease, self, button, tap, GetTime() - pressed)
  if not ok then self:Print("|cffff4040" .. tostring(err)) end
  self:Fire("INPUT")
end

function OP:Dispatch(button)
  if button == "START" then
    self.radial:Toggle()
    return
  end

  -- LB+RB is the shortcut chord wherever you are.
  if self.held.LB and self.held.RB and button ~= "LB" and button ~= "RB" then
    self:Shortcut(button)
    return
  end

  local capture = self:ActiveCapture()
  if capture then
    if button == "LT" or button == "RT" then
      if capture.Trigger then capture:Trigger(button) end
      return
    end
    if button ~= "LB" and button ~= "RB" and button ~= "L3" and button ~= "R3" then
      capture:Press(button)
      return
    end
  end

  if button == "LT" or button == "RT" or button == "LB" or button == "RB" then
    -- Layers and modifiers are read when the next button is pressed; bumper taps on release.
    return
  end
  if button == "L3" then
    ToggleAutoRun()
    return
  end
  if button == "R3" then
    return
  end

  local friendly, hostile = self:TargetModifiers()
  if self.held[friendly] then
    self.targeting:Friendly(button)
    return
  end
  if self.held[hostile] then
    self.targeting:Hostile(button)
    return
  end

  local layer = self:Layer()
  if layer == "BASE" and self.FACE[button] then
    self:FaceAction(button)
    return
  end
  self.actions:Press(self:Arrangement(), layer, button)
end

function OP:DispatchRelease(button, tap, duration)
  local capture = self:ActiveCapture()
  if button == "LB" or button == "RB" then
    if not tap then return end
    if capture then
      if capture.Bumper then capture:Bumper(button) end
      return
    end
    if self.held.LB or self.held.RB then return end
    local friendly = self:TargetModifiers()
    if button == friendly then self.targeting:TapFriendly() else self.targeting:TapHostile() end
    return
  end
  if button == "R3" then
    if self.lookingBehind then
      self.lookingBehind = nil
      if FlipCameraYaw then FlipCameraYaw(180) end
    elseif tap then
      -- A ping on your own position, the closest 1.12 has to Forever's world ping.
      Minimap:PingLocation(0, 0)
    end
    return
  end
  if capture and capture.Release then capture:Release(button) end
end

-- The fixed face buttons of the unmodified layer.
function OP:FaceAction(button)
  if button == "A" then
    if JumpOrAscendStart then JumpOrAscendStart() else Jump() end
  elseif button == "X" then
    -- The app right-clicks the reticle (talk, loot, attack what's in front of you). With nothing there,
    -- start attacking a hostile target.
    if not UnitExists("mouseover") and UnitExists("target") and UnitCanAttack("player", "target")
        and not UnitIsDead("target") and not self.attacking then
      AttackTarget()
    end
  elseif button == "Y" then
    -- Like a right-click on the unit frame: its menu (inspect, trade, invite, ...).
    if UnitExists("target") then
      ToggleDropDownMenu(1, nil, TargetFrameDropDown, "TargetFrame", 120, 10)
    else
      ToggleDropDownMenu(1, nil, PlayerFrameDropDown, "PlayerFrame", 106, 27)
    end
  elseif button == "B" then
    if SpellIsTargeting() then
      SpellStopTargeting()
    elseif CursorHasItem() or CursorHasSpell() then
      ClearCursor()
    elseif UnitExists("target") then
      ClearTarget()
    end
  end
end

-- LB+RB with another button.
function OP:Shortcut(button)
  if button == "B" then
    if self:AnyBagOpen() then CloseAllBags() else OpenAllBags() end
  elseif button == "X" then
    ToggleSheath()
  elseif button == "Y" then
    self.buffs:Toggle()
  elseif button == "UP" then
    ToggleQuestLog()
  elseif button == "DOWN" then
    self:OpenChat()
  elseif button == "RIGHT" then
    self:SetArrangement(self:Arrangement() + 1)
  elseif button == "LEFT" then
    self:SetArrangement(self:Arrangement() - 1)
  elseif button == "L3" then
    -- Recenter: the default camera view behind the character.
    if SetView then SetView(2) end
  elseif button == "R3" then
    if FlipCameraYaw then
      FlipCameraYaw(180)
      self.lookingBehind = true
    end
  end
end

function OP:AnyBagOpen()
  for i = 1, NUM_CONTAINER_FRAMES do
    local frame = getglobal("ContainerFrame" .. i)
    if frame and frame:IsVisible() then return true end
  end
  return false
end

function OP:OpenChat()
  ChatFrame_OpenChat("")
  -- The app shows its on-screen keyboard.
  self.bridge.keyboard = self.bridge.keyboard + 1
  self.bridge:Write(true)
end

-- ---- App bridge ----
-- VanillaHelpers' WriteFile is the only way out of a 1.12 client. The app reads the file to know when the
-- right stick should drive the pointer (menus) or keep the camera, and when to show its keyboard.

OP.bridge = { keyboard = 0, last = nil }

-- pointer: 0 camera, 1 menus want the pointer, 2 pointer with A/B as mouse buttons (ground targeting,
-- loading screens). capture: the face buttons are taken, so X must not click the world.
function OP.bridge:State()
  local pointer, capture = 0, 0
  if OP.leavingWorld or SpellIsTargeting() then
    pointer = 2
  elseif OP.nav:Active() or OP.radial:Active() or OP.buffs:Active() then
    pointer = 1
  end
  local c = OP:ActiveCapture()
  if pointer > 0 or c then capture = 1 end
  return pointer, capture
end

function OP.bridge:Write(force)
  if not WriteFile then return end
  local pointer, capture = self:State()
  local text = "pointer=" .. pointer .. "\ncapture=" .. capture .. "\nkeyboard=" .. self.keyboard
    .. "\nspeed=" .. string.format("%.2f", OP:Setting("cameraSpeed")) .. "\n"
  if text == self.last and not force then return end
  self.last = text
  pcall(WriteFile, "OctoPad.txt", "w", text)
end

-- ---- Events ----

local core = CreateFrame("Frame", "OctoPadCore", UIParent)
OP.frame = core
core:RegisterEvent("VARIABLES_LOADED")
core:RegisterEvent("PLAYER_ENTERING_WORLD")
core:RegisterEvent("PLAYER_LEAVING_WORLD")
core:RegisterEvent("PLAYER_LOGOUT")
core:RegisterEvent("UPDATE_BINDINGS")
core:RegisterEvent("PLAYER_ENTER_COMBAT")
core:RegisterEvent("PLAYER_LEAVE_COMBAT")

core:SetScript("OnEvent", function()
  if event == "VARIABLES_LOADED" then
    if not OctoPadDB then OctoPadDB = {} end
    if not OctoPadCharDB then OctoPadCharDB = {} end
    if not OctoPadCharDB.flyouts then OctoPadCharDB.flyouts = {} end
    OP.loaded = true
    OP:Fire("LOADED")
  elseif event == "PLAYER_ENTERING_WORLD" then
    OP.leavingWorld = nil
    OP:ApplyBindings()
    OP:Fire("WORLD")
    OP.bridge:Write(true)
  elseif event == "PLAYER_LEAVING_WORLD" or event == "PLAYER_LOGOUT" then
    OP.leavingWorld = true
    for button in pairs(OP.held) do OP.held[button] = nil end
    OP.bridge:Write(true)
  elseif event == "UPDATE_BINDINGS" then
    if OP.loaded then OP:ApplyBindings() end
  elseif event == "PLAYER_ENTER_COMBAT" then
    OP.attacking = true
  elseif event == "PLAYER_LEAVE_COMBAT" then
    OP.attacking = nil
  end
end)

-- Cheap enough to run every frame: only writes when the state changed.
core:SetScript("OnUpdate", function()
  if OP.loaded then OP.bridge:Write() end
end)

SLASH_OCTOPAD1 = "/octopad"
SlashCmdList["OCTOPAD"] = function(msg)
  OP.options:Toggle()
end
