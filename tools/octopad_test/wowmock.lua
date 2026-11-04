-- A small stand-in for the 1.12 client API, enough to load OctoPad and drive it with button presses.
-- Every API call is recorded in CALLS as "Name(arg1,arg2)".

CALLS = {}
local function record(name, ...)
  local parts = {}
  for i = 1, select("#", ...) do parts[i] = tostring((select(i, ...))) end
  table.insert(CALLS, name .. "(" .. table.concat(parts, ",") .. ")")
end

function stub(name, result)
  _G[name] = function(...)
    record(name, ...)
    if type(result) == "function" then return result(...) end
    return result
  end
end

NOW = 100
function GetTime() return NOW end

-- ---- Frames ----

FRAMES = {}
local Frame = {}
Frame.__index = function(t, k)
  local v = rawget(Frame, k)
  if v ~= nil then return v end
  -- Unknown methods (capitalised, like the real API) do nothing; unknown fields are nil.
  if string.find(k, "^%u") then return function() end end
  return nil
end

local function newObject(kind, name, parent)
  local f = setmetatable({ _kind = kind, _name = name, _parent = parent, _shown = true, _scripts = {},
    _children = {}, _events = {}, _w = 10, _h = 10, _x = 100, _y = 100, _id = 0 }, Frame)
  if parent and parent._children then table.insert(parent._children, f) end
  if name then _G[name] = f end
  return f
end

function CreateFrame(kind, name, parent, template)
  local f = newObject(kind, name, parent)
  table.insert(FRAMES, f)
  if template == "UICheckButtonTemplate" or template == "OptionsSliderTemplate" then
    newObject("FontString", name .. "Text", f)
    newObject("FontString", name .. "Low", f)
    newObject("FontString", name .. "High", f)
  end
  if template == "GameTooltipTemplate" then newObject("FontString", name .. "TextLeft1", f) end
  return f
end

function Frame:GetObjectType() return self._kind end
function Frame:GetName() return self._name end
function Frame:GetParent() return self._parent end
function Frame:Show() self._shown = true end
function Frame:Hide() self._shown = false end
function Frame:IsShown() return self._shown end
function Frame:IsVisible()
  local f = self
  while f do
    if not f._shown then return nil end
    f = f._parent
  end
  return 1
end
function Frame:SetParent(p) self._parent = p end
function Frame:SetScript(s, fn) self._scripts[s] = fn end
function Frame:GetScript(s) return self._scripts[s] end
function Frame:RegisterEvent(e) self._events[e] = true end
function Frame:GetChildren() return unpack(self._children) end
function Frame:CreateTexture(name) return newObject("Texture", name, self) end
function Frame:CreateFontString(name) return newObject("FontString", name, self) end
function Frame:SetText(t) self._text = t end
function Frame:GetText() return self._text end
function Frame:SetWidth(w) self._w = w end
function Frame:SetHeight(h) self._h = h end
function Frame:GetWidth() return self._w end
function Frame:GetHeight() return self._h end
function Frame:SetID(i) self._id = i end
function Frame:GetID() return self._id end
function Frame:SetPos(x, y) self._x, self._y = x, y end
function Frame:GetCenter() return self._x, self._y end
function Frame:GetEffectiveScale() return 1 end
function Frame:IsEnabled() return 1 end
function Frame:SetChecked(c) self._checked = c end
function Frame:GetChecked() return self._checked end
function Frame:SetValue(v) self._value = v; if self._scripts.OnValueChanged then local o = this; this = self; self._scripts.OnValueChanged(); this = o end end
function Frame:GetValue() return self._value or 0 end
function Frame:SetMinMaxValues(a, b) self._min, self._max = a, b end
function Frame:GetMinMaxValues() return self._min or 0, self._max or 1 end
function Frame:SetValueStep(s) self._step = s end
function Frame:GetValueStep() return self._step or 1 end
function Frame:Click()
  -- Like the client, a check box changes state before its OnClick runs.
  if self._kind == "CheckButton" then
    if self._checked then self._checked = nil else self._checked = 1 end
  end
  local fn = self._scripts.OnClick
  if not fn then return end
  local o, a = this, arg1
  this, arg1 = self, "LeftButton"
  fn()
  this, arg1 = o, a
end

UIParent = newObject("Frame", "UIParent")
UIParent._w, UIParent._h = 1024, 768
WorldFrame = newObject("Frame", "WorldFrame")
GameTooltip = newObject("GameTooltip", "GameTooltip")
GameTooltip._shown = false
GameTooltipTextLeft1 = newObject("FontString", "GameTooltipTextLeft1")
DEFAULT_CHAT_FRAME = { AddMessage = function(_, m) table.insert(CALLS, "Chat(" .. m .. ")") end }
Minimap = newObject("Frame", "Minimap")
function Minimap:PingLocation(x, y) record("PingLocation", x, y) end
for _, n in ipairs({ "MainMenuBar", "MultiBarBottomLeft", "MultiBarBottomRight", "MultiBarLeft", "MultiBarRight",
  "TargetFrameDropDown", "PlayerFrameDropDown", "GameMenuFrame", "SpellBookFrame" }) do
  newObject("Frame", n, UIParent)._shown = (n ~= "GameMenuFrame")
end
SpellBookFrame.bookType = "spell"

UIPanelWindows = {}
UISpecialFrames = {}
NUM_CONTAINER_FRAMES = 12
NUM_PET_ACTION_SLOTS = 10
BOOKTYPE_SPELL = "spell"
SlashCmdList = {}

function getglobal(n) return _G[n] end
function getfenv() return _G end

-- ---- World state the tests change ----

STATE = { target = nil, bonus = 0, party = 0, raid = 0, actions = {}, forms = {}, sp3 = false, combat = false,
  cursor = { 0, 0 }, mouseFocus = nil, spellTargeting = false }

function GetBonusBarOffset() return STATE.bonus end
function HasAction(slot) return STATE.actions[slot] ~= nil end
function GetActionTexture(slot) return STATE.actions[slot] end
function UnitExists(u)
  if u == "target" then return STATE.target ~= nil end
  if u == "player" then return true end
  if string.find(u, "^party(%d)") then return tonumber(string.sub(u, 6)) <= STATE.party end
  return false
end
function UnitCanAttack(a, b) return b == "target" and STATE.target == "enemy" end
function UnitIsDead() return false end
function UnitIsUnit(a, b) return a == b end
function UnitAffectingCombat() return STATE.combat end
function UnitClass() return "Warrior", "WARRIOR" end
function UnitName(u) return u end
function UnitXPMax() return 100 end
function UnitLevel() return 10 end
function GetNumPartyMembers() return STATE.party end
function GetNumRaidMembers() return STATE.raid end
function GetNumShapeshiftForms() return table.getn(STATE.forms) end
function GetShapeshiftFormInfo(i) local f = STATE.forms[i]; return f.texture, f.name, f.active, true end
function PetHasActionBar() return false end
function SpellIsTargeting() return STATE.spellTargeting end
function CursorHasItem() return false end
function CursorHasSpell() return false end
function GetCursorPosition() return STATE.cursor[1], STATE.cursor[2] end
function GetMouseFocus() return STATE.mouseFocus end
function GetGameTime() return 12, 5 end
function GetBindingAction() return "" end
function GetActionCooldown() return 0, 0, 0 end
function IsUsableAction() return 1 end
function IsActionInRange() return 1 end
function GetRaidTargetIndex() return nil end
function UnitXP(a, b)
  if STATE.sp3 then
    record("UnitXP", a, b)
    return true
  end
  return 0
end

for _, n in ipairs({ "SetBinding", "UseAction", "Jump", "AttackTarget", "ClearTarget", "SpellStopTargeting",
  "ClearCursor", "ToggleDropDownMenu", "TargetNearestEnemy", "TargetNearestFriend", "TargetLastEnemy", "TargetUnit",
  "AssistUnit", "SetRaidTarget", "PetAttack", "ToggleSheath", "ToggleQuestLog", "ToggleAutoRun", "SetView",
  "OpenAllBags", "CloseAllBags", "ChatFrame_OpenChat", "WriteFile", "CastShapeshiftForm", "CastPetAction",
  "ToggleCharacter", "ToggleSpellBook", "ToggleTalentFrame", "ToggleWorldMap", "ShowUIPanel", "HideUIPanel",
  "DoEmote", "SitOrStand", "PickupSpell", "PickupContainerItem", "PlaceAction", "PickupAction",
  "CooldownFrame_SetTimer", "FlipCameraYaw", "CloseDropDownMenus", "CancelPlayerBuff", "UseContainerItem",
  "IsConsumableAction", "IsCurrentAction", "IsAutoRepeatAction", "GetActionCount" }) do
  stub(n)
end

function FireEvent(name, a1)
  for _, f in ipairs(FRAMES) do
    if f._events[name] and f._scripts.OnEvent then
      this, event, arg1 = f, name, a1
      f._scripts.OnEvent()
    end
  end
end

function Update(elapsed)
  for _, f in ipairs(FRAMES) do
    if f._scripts.OnUpdate and f:IsVisible() then
      this, arg1 = f, elapsed or 0.3
      f._scripts.OnUpdate()
    end
  end
end

function Press(button) OctoPad_Button(button, "down"); NOW = NOW + 0.05 end
function Release(button) OctoPad_Button(button, "up"); NOW = NOW + 0.05 end
function Tap(button) Press(button); Release(button) end

function Called(prefix)
  for _, c in ipairs(CALLS) do
    if string.sub(c, 1, string.len(prefix)) == prefix then return true end
  end
  return false
end

function Reset() CALLS = {} end
