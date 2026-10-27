-- Flyouts, the radial menu on Start, the buff viewer (LB+RB+Y) and the small context menus that menu
-- navigation opens on Y.

local OP = OctoPad

local function iconButton(name, parent, size)
  local b = CreateFrame("Button", name, parent)
  b:SetWidth(size)
  b:SetHeight(size)
  b:RegisterForClicks("LeftButtonUp")
  b.bg = b:CreateTexture(nil, "BACKGROUND")
  b.bg:SetAllPoints(b)
  b.bg:SetTexture(0, 0, 0, 0.7)
  b.icon = b:CreateTexture(nil, "ARTWORK")
  b.icon:SetPoint("TOPLEFT", b, "TOPLEFT", 2, -2)
  b.icon:SetPoint("BOTTOMRIGHT", b, "BOTTOMRIGHT", -2, 2)
  b.icon:SetTexCoord(0.07, 0.93, 0.07, 0.93)
  b.selected = b:CreateTexture(nil, "OVERLAY")
  b.selected:SetAllPoints(b)
  b.selected:SetTexture("Interface\\Buttons\\CheckButtonHilight")
  b.selected:SetBlendMode("ADD")
  b.selected:Hide()
  local hl = b:CreateTexture(nil, "HIGHLIGHT")
  hl:SetAllPoints(b)
  hl:SetTexture("Interface\\Buttons\\ButtonHilight-Square")
  hl:SetBlendMode("ADD")
  return b
end

-- ---- Flyouts ----

local flyout = CreateFrame("Frame", "OctoPadFlyout", UIParent)
OP.flyout = flyout
flyout:SetFrameStrata("DIALOG")
flyout:SetHeight(64)
flyout:Hide()
flyout.KEYS = { "A", "X", "Y", "UP", "RIGHT", "DOWN", "LEFT" }
flyout.buttons = {}
flyout.title = flyout:CreateFontString(nil, "OVERLAY", "GameFontNormal")
flyout.title:SetPoint("BOTTOM", flyout, "TOP", 0, 2)

for i = 1, table.getn(flyout.KEYS) do
  local b = iconButton("OctoPadFlyoutButton" .. i, flyout, 40)
  b:SetPoint("LEFT", flyout, "LEFT", (i - 1) * 46 + 6, 0)
  b.glyph = b:CreateTexture(nil, "OVERLAY")
  b.glyph:SetWidth(18)
  b.glyph:SetHeight(18)
  b.glyph:SetPoint("TOP", b, "BOTTOM", 0, 2)
  b.glyph:SetTexture(OP.GLYPH .. string.lower(flyout.KEYS[i]))
  b.index = i
  b:SetScript("OnClick", function() flyout:Choose(this.index) end)
  b:SetScript("OnEnter", function()
    local option = flyout.options and flyout.options[this.index]
    if option then
      GameTooltip:SetOwner(this, "ANCHOR_TOP")
      GameTooltip:SetText(option.name)
    end
  end)
  b:SetScript("OnLeave", function() GameTooltip:Hide() end)
  flyout.buttons[i] = b
end

function flyout:Open(kind, anchor)
  if self:IsVisible() and self.kind == kind then
    self:Close()
    return
  end
  local def = OP.actions.FLYOUTS[kind]
  local options = def.Options()
  local count = math.min(table.getn(options), table.getn(self.KEYS))
  if count == 0 then
    OP:Print(def.title .. ": nothing to show yet.")
    return
  end
  self.kind = kind
  self.options = options
  self.title:SetText(def.title)
  for i, b in ipairs(self.buttons) do
    local option = options[i]
    if i <= count then
      b.icon:SetTexture(option.texture)
      if option.active then b.selected:Show() else b.selected:Hide() end
      b:Show()
    else
      b:Hide()
    end
  end
  self:SetWidth(count * 46 + 6)
  self:ClearAllPoints()
  self:SetPoint("BOTTOM", anchor or OctoPadBar, "TOP", 0, 14)
  self:Show()
end

function flyout:Choose(index)
  local option = self.options and self.options[index]
  if not option then return end
  option.cast()
  self:Close()
end

function flyout:Close()
  self:Hide()
  self.kind = nil
end

function flyout:Active()
  return self:IsVisible()
end

function flyout:Press(button)
  if button == "B" then
    self:Close()
    return
  end
  for i, key in ipairs(self.KEYS) do
    if key == button then self:Choose(i) end
  end
end

OP:AddCapture(flyout, 3)

-- ---- Buff viewer ----

local buffs = CreateFrame("Frame", "OctoPadBuffs", UIParent)
OP.buffs = buffs
table.insert(UISpecialFrames, "OctoPadBuffs")
buffs:SetFrameStrata("DIALOG")
buffs:SetWidth(240)
buffs:SetHeight(60)
buffs:SetPoint("TOPRIGHT", UIParent, "TOPRIGHT", -200, -120)
buffs:SetBackdrop({
  bgFile = "Interface\\DialogFrame\\UI-DialogBox-Background",
  edgeFile = "Interface\\DialogFrame\\UI-DialogBox-Border",
  tile = true, tileSize = 32, edgeSize = 24,
  insets = { left = 6, right = 6, top = 6, bottom = 6 },
})
buffs:EnableMouse(true)
buffs:Hide()
buffs.title = buffs:CreateFontString(nil, "OVERLAY", "GameFontNormal")
buffs.title:SetPoint("TOP", buffs, "TOP", 0, -12)
buffs.title:SetText("Buffs  (A: cancel, B: close)")
buffs.rows = {}

for i = 1, 16 do
  local b = iconButton("OctoPadBuff" .. i, buffs, 26)
  b:SetPoint("TOPLEFT", buffs, "TOPLEFT", 14, -30 - (i - 1) * 28)
  b.text = b:CreateFontString(nil, "OVERLAY", "GameFontHighlightSmall")
  b.text:SetPoint("LEFT", b, "RIGHT", 6, 0)
  b.text:SetWidth(180)
  b.text:SetJustifyH("LEFT")
  b:SetScript("OnClick", function()
    if this.buffIndex then CancelPlayerBuff(this.buffIndex) end
  end)
  b:SetScript("OnEnter", function()
    if this.buffIndex then
      GameTooltip:SetOwner(this, "ANCHOR_LEFT")
      GameTooltip:SetPlayerBuff(this.buffIndex)
    end
  end)
  b:SetScript("OnLeave", function() GameTooltip:Hide() end)
  buffs.rows[i] = b
end

local scanTip = CreateFrame("GameTooltip", "OctoPadScanTooltip", nil, "GameTooltipTemplate")
scanTip:SetOwner(WorldFrame, "ANCHOR_NONE")

function buffs:Refresh()
  local shown = 0
  for i = 1, 16 do
    local row = self.rows[i]
    local index = GetPlayerBuff(i - 1, "HELPFUL")
    if index and index >= 0 then
      shown = shown + 1
      row.buffIndex = index
      row.icon:SetTexture(GetPlayerBuffTexture(index))
      scanTip:SetOwner(WorldFrame, "ANCHOR_NONE")
      scanTip:SetPlayerBuff(index)
      local name = OctoPadScanTooltipTextLeft1:GetText() or ""
      local left = GetPlayerBuffTimeLeft(index)
      if left and left > 0 then name = name .. string.format("  |cffaaaaaa%d:%02d|r", math.floor(left / 60), math.mod(math.floor(left), 60)) end
      row.text:SetText(name)
      row:Show()
    else
      row.buffIndex = nil
      row:Hide()
    end
  end
  self:SetHeight(44 + math.max(shown, 1) * 28)
  if shown == 0 then self.title:SetText("No buffs") else self.title:SetText("Buffs  (A: cancel, B: close)") end
end

function buffs:Toggle()
  if self:IsVisible() then self:Hide() else self:Refresh(); self:Show() end
end

function buffs:Active()
  return self:IsVisible()
end

buffs:RegisterEvent("PLAYER_AURAS_CHANGED")
buffs:SetScript("OnEvent", function()
  if this:IsVisible() then this:Refresh() end
end)

-- ---- Context menu ----
-- A short list of choices opened by Y in menus (bind, split, destroy, share, ...). It is a menu like any
-- other to the navigation: the D-pad picks, A runs, B closes.

local menu = CreateFrame("Frame", "OctoPadMenu", UIParent)
OP.menu = menu
table.insert(UISpecialFrames, "OctoPadMenu")
menu:SetFrameStrata("FULLSCREEN_DIALOG")
menu:SetWidth(170)
menu:SetBackdrop({
  bgFile = "Interface\\Tooltips\\UI-Tooltip-Background",
  edgeFile = "Interface\\Tooltips\\UI-Tooltip-Border",
  tile = true, tileSize = 16, edgeSize = 16,
  insets = { left = 4, right = 4, top = 4, bottom = 4 },
})
menu:SetBackdropColor(0, 0, 0, 0.9)
menu:EnableMouse(true)
menu:Hide()
menu.title = menu:CreateFontString(nil, "OVERLAY", "GameFontNormal")
menu.title:SetPoint("TOP", menu, "TOP", 0, -8)
menu.buttons = {}
for i = 1, 6 do
  local b = CreateFrame("Button", "OctoPadMenuButton" .. i, menu, "UIPanelButtonTemplate")
  b:SetWidth(150)
  b:SetHeight(22)
  b:SetPoint("TOP", menu, "TOP", 0, -26 - (i - 1) * 24)
  b:SetScript("OnClick", function()
    local run = this.run
    menu:Hide()
    if run then run() end
  end)
  menu.buttons[i] = b
end

-- items: list of { text, run }.
function menu:Open(title, items, anchor)
  self.title:SetText(title or "")
  local count = math.min(table.getn(items), 6)
  for i, b in ipairs(self.buttons) do
    if i <= count then
      b:SetText(items[i][1])
      b.run = items[i][2]
      b:Show()
    else
      b:Hide()
    end
  end
  self:SetHeight(34 + count * 24)
  self:ClearAllPoints()
  if anchor then
    self:SetPoint("TOPLEFT", anchor, "TOPRIGHT", 8, 0)
  else
    self:SetPoint("CENTER", UIParent, "CENTER", 0, 0)
  end
  self:Show()
  OP:Fire("PANEL_OPENED", self)
end

-- ---- Radial menu ----

local radial = CreateFrame("Frame", "OctoPadRadial", UIParent)
OP.radial = radial
radial:SetFrameStrata("FULLSCREEN_DIALOG")
radial:SetWidth(380)
radial:SetHeight(380)
radial:SetPoint("CENTER", UIParent, "CENTER", 0, 0)
radial:Hide()

radial.ring = radial:CreateTexture(nil, "BACKGROUND")
radial.ring:SetPoint("CENTER", radial, "CENTER", 0, 0)
radial.ring:SetWidth(300)
radial.ring:SetHeight(300)
radial.ring:SetTexture("Interface\\Minimap\\UI-Minimap-Background")
radial.ring:SetVertexColor(0, 0, 0, 0.75)

radial.title = radial:CreateFontString(nil, "OVERLAY", "GameFontNormalLarge")
radial.title:SetPoint("CENTER", radial, "CENTER", 0, 26)
radial.label = radial:CreateFontString(nil, "OVERLAY", "GameFontHighlight")
radial.label:SetPoint("CENTER", radial, "CENTER", 0, 4)
radial.clock = radial:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
radial.clock:SetPoint("CENTER", radial, "CENTER", 0, -18)
radial.hint = radial:CreateFontString(nil, "OVERLAY", "GameFontDisableSmall")
radial.hint:SetPoint("CENTER", radial, "CENTER", 0, -40)
radial.hint:SetText("LB/RB: tabs   D-pad up: emotes   down: sit")

local function show(frame)
  if frame:IsVisible() then HideUIPanel(frame) else ShowUIPanel(frame) end
end

local PROFESSIONS = {
  "Alchemy", "Blacksmithing", "Enchanting", "Engineering", "Leatherworking", "Tailoring",
  "Cooking", "First Aid", "Smelting", "Poisons", "Jewelcrafting", "Survival",
}

local function knownProfessions()
  local known = {}
  local i = 1
  while true do
    local name = GetSpellName(i, BOOKTYPE_SPELL)
    if not name then break end
    known[name] = GetSpellTexture(i, BOOKTYPE_SPELL)
    i = i + 1
  end
  local items = {}
  for _, profession in ipairs(PROFESSIONS) do
    if known[profession] then
      local spell = profession
      table.insert(items, { profession, known[profession], function() CastSpellByName(spell) end })
    end
  end
  return items
end

local function emote(token, label)
  return { label, nil, function() DoEmote(token) end }
end

radial.TABS = {
  MAIN = {
    title = "Menu",
    items = function()
      return {
        { "Character", "Interface\\Icons\\INV_Chest_Cloth_17", function() ToggleCharacter("PaperDollFrame") end },
        { "Spellbook", "Interface\\Icons\\INV_Misc_Book_09", function() ToggleSpellBook(BOOKTYPE_SPELL) end },
        { "Talents", "Interface\\Icons\\Ability_Marksmanship", function() ToggleTalentFrame() end },
        { "Professions", "Interface\\Icons\\Trade_BlackSmithing", function() radial:Open("PROFESSIONS") end, true },
        { "Bags", "Interface\\Icons\\INV_Misc_Bag_08", function() OpenAllBags() end },
        { "Quests", "Interface\\Icons\\INV_Misc_Note_01", function() ToggleQuestLog() end },
        { "Map", "Interface\\Icons\\INV_Misc_Map_01", function() ToggleWorldMap() end },
        { "Chat", "Interface\\Icons\\INV_Letter_15", function() OP:OpenChat() end },
        { "Game Menu", "Interface\\Icons\\INV_Misc_Gear_01", function() show(GameMenuFrame) end },
      }
    end,
  },
  SOCIAL = {
    title = "Social",
    items = function()
      return {
        { "Friends", "Interface\\Icons\\INV_Misc_Head_Human_01", function() ToggleFriendsFrame(1) end },
        { "Who", "Interface\\Icons\\INV_Misc_Spyglass_02", function() ToggleFriendsFrame(2) end },
        { "Guild", "Interface\\Icons\\INV_Shirt_GuildTabard_01", function() ToggleFriendsFrame(3) end },
        { "Raid", "Interface\\Icons\\Ability_Warrior_BattleShout", function() ToggleFriendsFrame(4) end },
        { "Buffs", "Interface\\Icons\\Spell_Holy_WordFortitude", function() OP.buffs:Toggle() end },
        { "Key Ring", "Interface\\Icons\\INV_Misc_Key_14", function() ToggleKeyRing() end },
        { "Macros", "Interface\\Icons\\INV_Misc_Note_04", function()
          if not MacroFrame then MacroFrame_LoadUI() end
          if MacroFrame then show(MacroFrame) end
        end },
        { "Help", "Interface\\Icons\\INV_Misc_QuestionMark", function() if HelpFrame then show(HelpFrame) end end },
      }
    end,
  },
  SYSTEM = {
    title = "System",
    items = function()
      return {
        { "Honor", "Interface\\Icons\\INV_BannerPVP_02", function() ToggleCharacter("HonorFrame") end },
        { "Gamepad Options", "Interface\\Icons\\Trade_Engineering", function() OP.options:Toggle() end },
        { "Interface", "Interface\\Icons\\INV_Misc_Gear_02", function() if UIOptionsFrame then show(UIOptionsFrame) end end },
        { "Video", "Interface\\Icons\\INV_Misc_Spyglass_01", function() if OptionsFrame then show(OptionsFrame) end end },
        { "Sound", "Interface\\Icons\\INV_Misc_Drum_01", function() if SoundOptionsFrame then show(SoundOptionsFrame) end end },
        { "Logout", "Interface\\Icons\\Spell_Shadow_Teleport", function() Logout() end },
        { "Exit Game", "Interface\\Icons\\Spell_Shadow_SacrificialShield", function() Quit() end },
      }
    end,
  },
  EMOTE = {
    title = "Emotes",
    items = function()
      return {
        emote("WAVE", "Wave"), emote("HELLO", "Hello"), emote("BOW", "Bow"), emote("DANCE", "Dance"),
        emote("CHEER", "Cheer"), emote("LAUGH", "Laugh"), emote("THANK", "Thank"), emote("POINT", "Point"),
      }
    end,
  },
  PROFESSIONS = { title = "Professions", items = knownProfessions },
}
radial.ORDER = { "MAIN", "SOCIAL", "SYSTEM" }

radial.buttons = {}
for i = 1, 12 do
  local b = iconButton("OctoPadRadialButton" .. i, radial, 44)
  b.text = b:CreateFontString(nil, "OVERLAY", "GameFontHighlightSmall")
  b.text:SetPoint("TOP", b, "BOTTOM", 0, -1)
  b.index = i
  b:SetScript("OnClick", function() radial:Run(this.index) end)
  b:SetScript("OnEnter", function() radial:Select(this.index) end)
  radial.buttons[i] = b
end

function radial:Open(tab)
  self.tab = tab
  local items = self.TABS[tab].items()
  self.items = items
  local count = math.min(table.getn(items), table.getn(self.buttons))
  self.count = count
  self.title:SetText(self.TABS[tab].title)
  for i, b in ipairs(self.buttons) do
    if i <= count then
      local angle = (i - 1) / count * 2 * math.pi
      b:ClearAllPoints()
      b:SetPoint("CENTER", self, "CENTER", 120 * math.sin(angle), 120 * math.cos(angle))
      b.icon:SetTexture(items[i][2] or "Interface\\Icons\\INV_Misc_Toy_07")
      b.text:SetText(items[i][1])
      b:Show()
    else
      b:Hide()
    end
  end
  self.selected = nil
  self.label:SetText(count == 0 and "Nothing here" or "")
  self.cursor = nil
  self:Show()
  self:Select(1)
end

function radial:Select(index)
  if not index or index < 1 or index > (self.count or 0) then return end
  self.selected = index
  for i, b in ipairs(self.buttons) do
    if i == index then b.selected:Show() else b.selected:Hide() end
  end
  self.label:SetText(self.items[index][1])
end

function radial:Run(index)
  local item = self.items and self.items[index]
  if not item then return end
  -- Items that open another ring keep the menu up.
  if not item[4] then self:Hide() end
  item[3]()
end

function radial:Toggle()
  if self:IsVisible() then self:Hide() else self:Open("MAIN") end
end

function radial:Active()
  return self:IsVisible()
end

function radial:Press(button)
  if button == "A" then
    self:Run(self.selected)
  elseif button == "B" then
    if self.tab == "EMOTE" or self.tab == "PROFESSIONS" then self:Open("MAIN") else self:Hide() end
  elseif button == "RIGHT" or button == "LEFT" then
    local step = button == "RIGHT" and 1 or -1
    local index = (self.selected or 0) + step
    if index > self.count then index = 1 elseif index < 1 then index = self.count end
    self:Select(index)
  elseif button == "UP" then
    self:Open("EMOTE")
  elseif button == "DOWN" then
    self:Hide()
    SitOrStand()
  end
end

function radial:Bumper(button)
  local current = 1
  for i, tab in ipairs(self.ORDER) do
    if tab == self.tab then current = i end
  end
  current = current + (button == "RB" and 1 or -1)
  if current > table.getn(self.ORDER) then current = 1 elseif current < 1 then current = table.getn(self.ORDER) end
  self:Open(self.ORDER[current])
end

-- The right stick moves the pointer while the menu is up: point at an item to select it.
radial:SetScript("OnUpdate", function()
  local hour, minute = GetGameTime()
  this.clock:SetText(string.format("%02d:%02d", hour, minute))
  local x, y = GetCursorPosition()
  if this.cursor == nil then
    this.cursor = x .. "," .. y
    return
  end
  if this.cursor == x .. "," .. y or (this.count or 0) == 0 then return end
  this.cursor = x .. "," .. y
  local scale = this:GetEffectiveScale()
  local cx, cy = this:GetCenter()
  local dx, dy = x / scale - cx, y / scale - cy
  if dx * dx + dy * dy < 40 * 40 then return end
  -- Angle clockwise from the top, the way the items are laid out.
  local angle = math.atan2(dx, dy)
  if angle < 0 then angle = angle + 2 * math.pi end
  local index = math.floor(angle / (2 * math.pi) * this.count + 0.5)
  this:Select(math.mod(index, this.count) + 1)
end)

OP:AddCapture(radial, 2)
