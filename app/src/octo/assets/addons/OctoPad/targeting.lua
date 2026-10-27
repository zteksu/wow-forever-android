-- Forever's bumper targeting. A tap on the hostile bumper (RB) picks the enemy in front of you and cycles
-- on further taps; a tap on the friendly bumper (LB) picks a friend. Held, each bumper turns the D-pad and
-- face buttons into targeting actions. UnitXP_SP3's facing- and distance-aware targeting is used when the
-- client has it.

local OP = OctoPad
local targeting = {}
OP.targeting = targeting

-- Without the DLL, UnitXP is the stock XP function and would swallow these calls.
local function hasSP3()
  if targeting.sp3 == nil then
    local ok, result = pcall(UnitXP, "nop", "nop")
    targeting.sp3 = ok and result == true
  end
  return targeting.sp3
end

-- nil when UnitXP_SP3 isn't loaded, so callers fall back to the stock functions.
local function unitXP(command)
  if not hasSP3() then return nil end
  local ok, result = pcall(UnitXP, "target", command)
  if ok then return result end
  return nil
end

local function hasHostileTarget()
  return UnitExists("target") and UnitCanAttack("player", "target") and not UnitIsDead("target")
end

function targeting:NearestEnemy()
  if unitXP("nearestEnemy") == nil then TargetNearestEnemy() end
end

function targeting:TapHostile()
  if hasHostileTarget() then
    if unitXP("nextEnemyConsideringDistance") == nil then TargetNearestEnemy() end
  else
    self:NearestEnemy()
  end
end

function targeting:TapFriendly()
  TargetNearestFriend()
end

function targeting:CycleMark()
  if not UnitExists("target") or not SetRaidTarget then return end
  local index = (GetRaidTargetIndex("target") or 0) + 1
  if index > 8 then index = 0 end
  SetRaidTarget("target", index)
end

-- Group members in the order the group frames show them.
function targeting:GroupUnits()
  local units = {}
  local raid = GetNumRaidMembers()
  if raid > 0 then
    local groups = {}
    for i = 1, raid do
      local _, _, subgroup = GetRaidRosterInfo(i)
      subgroup = subgroup or 1
      if not groups[subgroup] then groups[subgroup] = {} end
      table.insert(groups[subgroup], "raid" .. i)
    end
    for g = 1, 8 do
      if groups[g] then
        for _, unit in ipairs(groups[g]) do table.insert(units, { unit = unit, group = g }) end
      end
    end
  else
    table.insert(units, { unit = "player", group = 1 })
    for i = 1, GetNumPartyMembers() do table.insert(units, { unit = "party" .. i, group = 1 }) end
  end
  return units
end

-- Moves the target through the group frames: up/down within the list, left/right a raid group at a time.
function targeting:StepGroup(direction)
  local units = self:GroupUnits()
  local count = table.getn(units)
  if count == 0 then return end
  local current
  for i = 1, count do
    if UnitIsUnit("target", units[i].unit) then current = i end
  end
  local nextIndex
  if not current then
    nextIndex = 1
  elseif direction == "UP" then
    nextIndex = current - 1
    if nextIndex < 1 then nextIndex = count end
  elseif direction == "DOWN" then
    nextIndex = current + 1
    if nextIndex > count then nextIndex = 1 end
  else
    local step = direction == "RIGHT" and 1 or -1
    local group = units[current].group
    nextIndex = current
    for _ = 1, count do
      nextIndex = nextIndex + step
      if nextIndex > count then nextIndex = 1 elseif nextIndex < 1 then nextIndex = count end
      if units[nextIndex].group ~= group then break end
    end
    -- Land on the first member of that group.
    local g = units[nextIndex].group
    while nextIndex > 1 and units[nextIndex - 1].group == g do nextIndex = nextIndex - 1 end
  end
  TargetUnit(units[nextIndex].unit)
end

local FIXED_PARTY = { UP = "party1", RIGHT = "party2", DOWN = "party3", LEFT = "party4" }

local function friendlyDpad(button)
  if OP:Setting("fixedParty") and GetNumRaidMembers() == 0 and GetNumPartyMembers() > 0 then
    local unit = FIXED_PARTY[button]
    if UnitExists(unit) then TargetUnit(unit) end
    return
  end
  targeting:StepGroup(button)
end

local function friendlyFace(button)
  if button == "A" then
    TargetUnit("player")
  elseif button == "X" then
    if UnitExists("pet") then TargetUnit("pet") end
  elseif button == "B" then
    if UnitExists("target") then AssistUnit("target") end
  elseif button == "Y" then
    targeting:CycleMark()
  end
end

local function hostileDpad(button)
  if button == "LEFT" then
    if unitXP("previousEnemyConsideringDistance") == nil then TargetNearestEnemy(1) end
  elseif button == "RIGHT" then
    if unitXP("nextEnemyConsideringDistance") == nil then TargetNearestEnemy() end
  elseif button == "UP" then
    targeting:NearestEnemy()
  elseif button == "DOWN" then
    TargetLastEnemy()
  end
end

local function hostileFace(button)
  if button == "A" then
    if hasHostileTarget() then
      if not OP.attacking then AttackTarget() end
      if UnitExists("pet") then PetAttack() end
    end
  elseif button == "X" then
    -- Raid markers first: skull, cross, ...
    if unitXP("nextMarkedEnemyInCycle") == nil then TargetNearestEnemy() end
  elseif button == "Y" then
    targeting:CycleMark()
  elseif button == "B" then
    ClearTarget()
  end
end

-- "Swap ... Target Action Sides" moves the D-pad actions onto the face buttons and back.
local function route(button, swap, dpad, face)
  if swap then button = OP.MIRROR[button] end
  if OP.DPAD[button] then dpad(button) else face(button) end
end

function targeting:Friendly(button)
  route(button, OP:Setting("swapFriendlyActions"), friendlyDpad, friendlyFace)
end

function targeting:Hostile(button)
  route(button, OP:Setting("swapHostileActions"), hostileDpad, hostileFace)
end
