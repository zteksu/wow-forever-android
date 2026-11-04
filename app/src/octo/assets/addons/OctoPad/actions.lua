-- The gamepad action bar: three arrangements of LT, RT and LT+RT layers over the D-pad and face buttons,
-- plus the four unmodified D-pad slots shared by every arrangement. Any slot can hold a class flyout
-- (stances/forms/auras, pet control, pet spells) instead of an action.

local OP = OctoPad
local actions = {}
OP.actions = actions

local NUM_PAGES = 6

-- Action slot of each layer's first button. Arrangement 1's LT layer and the shared D-pad are action bar
-- 1 (slots 1-12), which swaps with stances and forms like the default bar. Slots 109-120 are the one
-- bonus bar no 1.12 class uses.
local LAYER_START = {
  [1] = { LT = 1, RT = 13, LTRT = 21 },
  [2] = { LT = 29, RT = 37, LTRT = 45 },
  [3] = { LT = 53, RT = 61, LTRT = 109 },
}
local BASE_START = 9

local INDEX = {}
for i, b in ipairs(OP.ACTION_BUTTONS) do INDEX[b] = i end
local DPAD_INDEX = { UP = 1, RIGHT = 2, DOWN = 3, LEFT = 4 }

local function paged(slot)
  local offset = GetBonusBarOffset()
  if offset > 0 then return slot + (NUM_PAGES + offset - 1) * 12 end
  return slot
end

-- nil for the fixed face buttons of the unmodified layer.
function actions:Slot(arrangement, layer, button)
  if layer == "BASE" then
    if not DPAD_INDEX[button] then return nil end
    return paged(BASE_START + DPAD_INDEX[button] - 1)
  end
  local slot = LAYER_START[arrangement][layer] + INDEX[button] - 1
  if arrangement == 1 and layer == "LT" then return paged(slot) end
  return slot
end

-- Flyouts are stored per character under arrangement:layer:button (the shared D-pad uses arrangement 0).
function actions:Key(arrangement, layer, button)
  if layer == "BASE" then arrangement = 0 end
  return arrangement .. ":" .. layer .. ":" .. button
end

function actions:Flyout(arrangement, layer, button)
  return OctoPadCharDB.flyouts[self:Key(arrangement, layer, button)]
end

function actions:Press(arrangement, layer, button)
  local kind = self:Flyout(arrangement, layer, button)
  if kind then
    OP.flyout:Open(kind, self:ButtonFrame(layer, button))
    return
  end
  local slot = self:Slot(arrangement, layer, button)
  if not slot or not HasAction(slot) then return end
  -- Forever's soft targeting: an action with nothing targeted picks the enemy in front of you.
  if OP:Setting("autoTarget") and UnitAffectingCombat("player")
      and (not UnitExists("target") or UnitIsDead("target")) then
    OP.targeting:NearestEnemy()
  end
  UseAction(slot, 0)
end

-- ---- Flyouts ----

-- Each returns a list of { texture, name, active, cast = function }.
actions.FLYOUTS = {}

actions.FLYOUTS.stance = {
  title = "Stances",
  Options = function()
    local list = {}
    for i = 1, GetNumShapeshiftForms() do
      local texture, name, active = GetShapeshiftFormInfo(i)
      local index = i
      table.insert(list, { texture = texture, name = name, active = active,
        cast = function() CastShapeshiftForm(index) end })
    end
    return list
  end,
}

local PET_CONTROL = {
  PET_ACTION_ATTACK = true, PET_ACTION_FOLLOW = true, PET_ACTION_WAIT = true,
  PET_MODE_AGGRESSIVE = true, PET_MODE_DEFENSIVE = true, PET_MODE_PASSIVE = true,
}

local function petOptions(control)
  local list = {}
  if not PetHasActionBar() then return list end
  for i = 1, NUM_PET_ACTION_SLOTS do
    local name, subtext, texture, isToken, active = GetPetActionInfo(i)
    if name and (PET_CONTROL[name] and true or false) == control then
      local index = i
      if isToken then
        texture = getglobal(texture)
        name = getglobal(name)
      end
      table.insert(list, { texture = texture, name = name, active = active,
        cast = function() CastPetAction(index) end })
    end
  end
  return list
end

actions.FLYOUTS.petcontrol = { title = "Pet Control", Options = function() return petOptions(true) end }
actions.FLYOUTS.petspells = { title = "Pet Spells", Options = function() return petOptions(false) end }

-- First login per character: put the class's flyouts on free shared D-pad slots, as Forever adds them to
-- the bar.
function actions:AssignDefaultFlyouts()
  local db = OctoPadCharDB
  if not db.defaults then db.defaults = {} end
  local function assign(kind, button)
    if db.defaults[kind] then return end
    local key = self:Key(0, "BASE", button)
    if not db.flyouts[key] and not HasAction(self:Slot(1, "BASE", button)) then db.flyouts[key] = kind end
    db.defaults[kind] = true
  end
  if GetNumShapeshiftForms() > 0 then assign("stance", "LEFT") end
  local _, class = UnitClass("player")
  if class == "HUNTER" or class == "WARLOCK" then
    assign("petcontrol", "DOWN")
    assign("petspells", "RIGHT")
  end
end

-- ---- Bar ----

local SIZE = 38
local GAP = 4

local bar = CreateFrame("Frame", "OctoPadBar", UIParent)
actions.bar = bar
bar:SetWidth(4 * SIZE)
bar:SetHeight(2 * SIZE)
bar:SetFrameStrata("MEDIUM")

local clusters = {}
actions.clusters = clusters

-- Positions of the buttons in a D-pad or face diamond.
local DIAMOND = {
  UP = { 0, 1 }, RIGHT = { 1, 0 }, DOWN = { 0, -1 }, LEFT = { -1, 0 },
  Y = { 0, 1 }, B = { 1, 0 }, A = { 0, -1 }, X = { -1, 0 },
}

local FIXED_FACE = { A = "Jump", X = "Interact", Y = "Menu", B = "Cancel" }

local function createButton(cluster, button)
  local name = "OctoPad" .. cluster.layer .. button
  local f = CreateFrame("Button", name, cluster)
  f:SetWidth(SIZE)
  f:SetHeight(SIZE)
  f:RegisterForClicks("LeftButtonUp", "RightButtonUp")
  f:RegisterForDrag("LeftButton")
  f.layer = cluster.layer
  f.button = button

  f.bg = f:CreateTexture(nil, "BACKGROUND")
  f.bg:SetAllPoints(f)
  f.bg:SetTexture(0, 0, 0, 0.55)

  f.icon = f:CreateTexture(nil, "ARTWORK")
  f.icon:SetPoint("TOPLEFT", f, "TOPLEFT", 2, -2)
  f.icon:SetPoint("BOTTOMRIGHT", f, "BOTTOMRIGHT", -2, 2)
  f.icon:SetTexCoord(0.07, 0.93, 0.07, 0.93)

  f.cooldown = CreateFrame("Model", name .. "Cooldown", f, "CooldownFrameTemplate")
  f.cooldown:SetAllPoints(f.icon)

  f.label = f:CreateFontString(nil, "OVERLAY", "GameFontHighlightSmall")
  f.label:SetPoint("CENTER", f, "CENTER", 0, 0)

  f.count = f:CreateFontString(nil, "OVERLAY", "NumberFontNormalSmall")
  f.count:SetPoint("BOTTOMRIGHT", f, "BOTTOMRIGHT", -2, 2)

  f.glyph = f:CreateTexture(nil, "OVERLAY")
  f.glyph:SetWidth(16)
  f.glyph:SetHeight(16)
  f.glyph:SetPoint("TOPLEFT", f, "TOPLEFT", -4, 4)
  f.glyph:SetTexture(OP.GLYPH .. string.lower(button))

  f.flyoutArrow = f:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
  f.flyoutArrow:SetPoint("TOPRIGHT", f, "TOPRIGHT", -2, -1)
  f.flyoutArrow:SetText("^")

  f.highlight = f:CreateTexture(nil, "HIGHLIGHT")
  f.highlight:SetAllPoints(f)
  f.highlight:SetTexture("Interface\\Buttons\\ButtonHilight-Square")
  f.highlight:SetBlendMode("ADD")

  f.active = f:CreateTexture(nil, "OVERLAY")
  f.active:SetAllPoints(f)
  f.active:SetTexture("Interface\\Buttons\\CheckButtonHilight")
  f.active:SetBlendMode("ADD")
  f.active:Hide()

  -- Pointer users can click, drag spells in and out, like any action button.
  f:SetScript("OnClick", function()
    local slot = this.slot
    if this.flyout then
      OP.flyout:Open(this.flyout, this)
    elseif slot then
      if CursorHasSpell() or CursorHasItem() then
        actions:Place(this)
      else
        UseAction(slot, 1)
      end
    end
  end)
  f:SetScript("OnDragStart", function()
    if this.slot and not this.flyout then
      PickupAction(this.slot)
      actions:Update()
    end
  end)
  f:SetScript("OnReceiveDrag", function() actions:Place(this) end)
  f:SetScript("OnEnter", function()
    if this.slot and not this.flyout then
      GameTooltip:SetOwner(this, "ANCHOR_RIGHT")
      GameTooltip:SetAction(this.slot)
    elseif this.flyout then
      GameTooltip:SetOwner(this, "ANCHOR_RIGHT")
      GameTooltip:SetText(actions.FLYOUTS[this.flyout].title)
    elseif this.fixed then
      GameTooltip:SetOwner(this, "ANCHOR_RIGHT")
      GameTooltip:SetText(this.fixed)
    end
  end)
  f:SetScript("OnLeave", function() GameTooltip:Hide() end)
  return f
end

-- Dropping a spell or item on a bar button: the slot takes it and loses any flyout.
function actions:Place(f)
  if not f.slot then return end
  OctoPadCharDB.flyouts[self:Key(OP:Arrangement(), f.layer, f.button)] = nil
  PlaceAction(f.slot)
  self:Update()
end

local function createCluster(layer)
  local c = CreateFrame("Frame", "OctoPadCluster" .. layer, bar)
  c.layer = layer
  c:SetWidth(2 * (3 * SIZE + 2 * GAP) + 24)
  c:SetHeight(3 * SIZE + 2 * GAP)
  c.buttons = {}
  for _, b in ipairs(OP.ACTION_BUTTONS) do
    local f = createButton(c, b)
    local pos = DIAMOND[b]
    local side = OP.FACE[b] and 1 or -1
    local cx = side * (1.5 * SIZE + GAP + 12)
    f:SetPoint("CENTER", c, "CENTER", cx + pos[1] * (SIZE + GAP), pos[2] * (SIZE + GAP))
    c.buttons[b] = f
  end
  c.title = c:CreateTexture(nil, "OVERLAY")
  c.title:SetWidth(24)
  c.title:SetHeight(24)
  c.title:SetPoint("CENTER", c, "CENTER", 0, 0)
  c.title2 = c:CreateTexture(nil, "OVERLAY")
  c.title2:SetWidth(24)
  c.title2:SetHeight(24)
  c.title2:SetPoint("CENTER", c, "CENTER", 0, -20)
  if layer == "LT" or layer == "RT" then
    c.title:SetTexture(OP.GLYPH .. string.lower(layer))
  elseif layer == "LTRT" then
    c.title:SetTexture(OP.GLYPH .. "lt")
    c.title:SetPoint("CENTER", c, "CENTER", 0, 12)
    c.title2:SetTexture(OP.GLYPH .. "rt")
    c.title2:SetPoint("CENTER", c, "CENTER", 0, -12)
  end
  if layer ~= "LTRT" then c.title2:Hide() end
  clusters[layer] = c
  return c
end

for _, layer in ipairs({ "BASE", "LT", "RT", "LTRT" }) do createCluster(layer) end

bar.arrangement = bar:CreateFontString(nil, "OVERLAY", "GameFontNormal")
bar.arrangement:SetPoint("BOTTOM", bar, "TOP", 0, 4)

function actions:Layout()
  local compact = OP:Setting("compact")
  local active = OP.capturedLayer or OP:Layer()
  bar:SetScale(OP:Setting("scale"))
  local cw, ch = clusters.BASE:GetWidth(), clusters.BASE:GetHeight()
  if compact then
    bar:SetWidth(cw)
    bar:SetHeight(ch)
    for layer, c in pairs(clusters) do
      c:ClearAllPoints()
      c:SetPoint("CENTER", bar, "CENTER", 0, 0)
      if layer == active then c:Show() else c:Hide() end
      c:SetAlpha(1)
    end
  else
    -- Two by two: the unmodified layer and LT below, RT and LT+RT above, the active one bright.
    bar:SetWidth(2 * cw + 16)
    bar:SetHeight(2 * ch + 8)
    local spots = { BASE = { -1, -1 }, LT = { 1, -1 }, RT = { -1, 1 }, LTRT = { 1, 1 } }
    for layer, c in pairs(clusters) do
      local s = spots[layer]
      c:ClearAllPoints()
      c:SetPoint("CENTER", bar, "CENTER", s[1] * (cw / 2 + 8), s[2] * (ch / 2 + 4))
      c:Show()
      if layer == active then c:SetAlpha(1) else c:SetAlpha(0.55) end
    end
  end
  bar.arrangement:SetText("Bars " .. OP:Arrangement() .. "/3")
  local pos = OctoPadDB.barPos
  bar:ClearAllPoints()
  if pos then
    bar:SetPoint("BOTTOM", UIParent, "BOTTOM", pos.x, pos.y)
  else
    bar:SetPoint("BOTTOM", UIParent, "BOTTOM", 0, 14)
  end
end

local function updateButton(f, arrangement)
  local layer, button = f.layer, f.button
  f.slot = actions:Slot(arrangement, layer, button)
  f.flyout = f.slot and actions:Flyout(arrangement, layer, button)
  f.fixed = not f.slot and FIXED_FACE[button]
  f.label:SetText("")
  f.count:SetText("")
  f.active:Hide()
  f.flyoutArrow:Hide()
  f.icon:SetVertexColor(1, 1, 1)
  if f.fixed then
    f.icon:SetTexture(nil)
    f.label:SetText(f.fixed)
    f.cooldown:Hide()
    return
  end
  if f.flyout then
    local options = actions.FLYOUTS[f.flyout].Options()
    local texture = options[1] and options[1].texture
    for i = 1, table.getn(options) do
      if options[i].active then texture = options[i].texture end
    end
    f.icon:SetTexture(texture or "Interface\\Icons\\INV_Misc_QuestionMark")
    f.flyoutArrow:Show()
    f.cooldown:Hide()
    return
  end
  local texture = GetActionTexture(f.slot)
  f.icon:SetTexture(texture)
  if not texture then
    f.cooldown:Hide()
    return
  end
  if IsConsumableAction(f.slot) then f.count:SetText(GetActionCount(f.slot)) end
  if IsCurrentAction(f.slot) or IsAutoRepeatAction(f.slot) then f.active:Show() end
  local start, duration, enable = GetActionCooldown(f.slot)
  CooldownFrame_SetTimer(f.cooldown, start, duration, enable)
  local usable, noMana = IsUsableAction(f.slot)
  if IsActionInRange(f.slot) == 0 then
    f.icon:SetVertexColor(0.9, 0.2, 0.2)
  elseif usable then
    f.icon:SetVertexColor(1, 1, 1)
  elseif noMana then
    f.icon:SetVertexColor(0.4, 0.4, 1)
  else
    f.icon:SetVertexColor(0.4, 0.4, 0.4)
  end
end

function actions:Update()
  if not OP.loaded then return end
  local arrangement = OP:Arrangement()
  for _, c in pairs(clusters) do
    for _, f in pairs(c.buttons) do updateButton(f, arrangement) end
  end
end

-- The bar button showing [layer]+[button] right now, to anchor a flyout.
function actions:ButtonFrame(layer, button)
  return clusters[layer].buttons[button]
end

local events = {
  "ACTIONBAR_SLOT_CHANGED", "ACTIONBAR_UPDATE_STATE", "ACTIONBAR_UPDATE_USABLE",
  "ACTIONBAR_UPDATE_COOLDOWN", "UPDATE_BONUS_ACTIONBAR", "PLAYER_TARGET_CHANGED", "BAG_UPDATE",
  "UPDATE_SHAPESHIFT_FORMS", "PET_BAR_UPDATE", "UNIT_PET", "PLAYER_AURAS_CHANGED",
  "START_AUTOREPEAT_SPELL", "STOP_AUTOREPEAT_SPELL", "PLAYER_ENTER_COMBAT", "PLAYER_LEAVE_COMBAT",
}
for _, e in ipairs(events) do bar:RegisterEvent(e) end
bar:SetScript("OnEvent", function()
  if event == "UPDATE_SHAPESHIFT_FORMS" or event == "PET_BAR_UPDATE" or event == "UNIT_PET" then
    if OP.loaded then actions:AssignDefaultFlyouts() end
  end
  actions:Update()
end)

-- Range and usability change without events; check a few times a second.
bar.elapsed = 0
bar:SetScript("OnUpdate", function()
  this.elapsed = this.elapsed + arg1
  if this.elapsed < 0.2 then return end
  this.elapsed = 0
  actions:Update()
end)

OP:On("INPUT", function()
  local layer = OP:Layer()
  if layer ~= actions.shownLayer then
    actions.shownLayer = layer
    actions:Layout()
  end
end)
OP:On("LAYOUT", function() actions:Layout(); actions:Update() end)
OP:On("SETTING", function() actions:Layout(); actions:Update(); actions:HideBlizzardBars() end)
OP:On("WORLD", function()
  actions:AssignDefaultFlyouts()
  actions:HideBlizzardBars()
  actions:Layout()
  actions:Update()
end)

-- ---- Default UI ----

local hiddenParent = CreateFrame("Frame", nil, UIParent)
hiddenParent:Hide()

-- Forever's gamepad bar replaces the action bars; bags and menus live in the radial menu.
function actions:HideBlizzardBars()
  if not OP:Setting("hideBlizzardBars") or self.barsHidden then return end
  self.barsHidden = true
  local frames = { MainMenuBar, MultiBarBottomLeft, MultiBarBottomRight, MultiBarLeft, MultiBarRight }
  for _, f in ipairs(frames) do
    if f then f:SetParent(hiddenParent) end
  end
  actions.xp:Show()
end

-- A slim experience bar under the gamepad bar, since the default one goes with the main menu bar.
local xp = CreateFrame("StatusBar", "OctoPadXPBar", UIParent)
actions.xp = xp
xp:SetHeight(6)
xp:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMLEFT", 0, 0)
xp:SetPoint("BOTTOMRIGHT", UIParent, "BOTTOMRIGHT", 0, 0)
xp:SetStatusBarTexture("Interface\\TargetingFrame\\UI-StatusBar")
xp:SetStatusBarColor(0.58, 0.0, 0.55)
xp.bg = xp:CreateTexture(nil, "BACKGROUND")
xp.bg:SetAllPoints(xp)
xp.bg:SetTexture(0, 0, 0, 0.6)
xp:Hide()
xp:RegisterEvent("PLAYER_XP_UPDATE")
xp:RegisterEvent("PLAYER_LEVEL_UP")
xp:RegisterEvent("PLAYER_ENTERING_WORLD")
xp:SetScript("OnEvent", function()
  local max = UnitXPMax("player")
  if not max or max == 0 then return end
  this:SetMinMaxValues(0, max)
  this:SetValue(UnitXP("player"))
  if UnitLevel("player") >= 60 then this:SetAlpha(0) else this:SetAlpha(1) end
end)

-- ---- Interact prompt ----
-- The app parks the pointer on the reticle, so whatever is under it is what X will interact with.

local RETICLE_X, RETICLE_Y = 0.5, 0.45

local prompt = CreateFrame("Frame", "OctoPadInteractPrompt", UIParent)
prompt:SetWidth(200)
prompt:SetHeight(20)
prompt:SetFrameStrata("HIGH")
prompt.glyph = prompt:CreateTexture(nil, "OVERLAY")
prompt.glyph:SetWidth(20)
prompt.glyph:SetHeight(20)
prompt.glyph:SetTexture(OP.GLYPH .. "x")
prompt.text = prompt:CreateFontString(nil, "OVERLAY", "GameFontNormal")
prompt.text:SetPoint("LEFT", prompt.glyph, "RIGHT", 4, 0)
prompt.glyph:SetPoint("LEFT", prompt, "LEFT", 0, 0)
prompt.elapsed = 0
prompt:SetScript("OnUpdate", function()
  this.elapsed = this.elapsed + arg1
  if this.elapsed < 0.1 then return end
  this.elapsed = 0
  local name
  if OP:Setting("interactPrompt") and not OP:ActiveCapture() then
    if UnitExists("mouseover") then
      name = UnitName("mouseover")
    elseif GameTooltip:IsVisible() and GameTooltip.GetOwner and GameTooltip:GetOwner() == UIParent then
      name = GameTooltipTextLeft1:GetText()
    end
  end
  if name then
    this:ClearAllPoints()
    this:SetPoint("LEFT", UIParent, "BOTTOMLEFT", UIParent:GetWidth() * RETICLE_X + 16,
      UIParent:GetHeight() * (1 - RETICLE_Y) - 24)
    this.text:SetText(name)
    this:SetAlpha(1)
  else
    this:SetAlpha(0)
  end
end)
