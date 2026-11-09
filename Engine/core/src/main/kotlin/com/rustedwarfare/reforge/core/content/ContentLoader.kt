package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.content.internal.IniReader
import com.rustedwarfare.reforge.core.content.internal.BuilderContentReader
import com.rustedwarfare.reforge.core.content.internal.CommandCenterContentReader
import com.rustedwarfare.reforge.core.content.internal.CombatContentReader
import com.rustedwarfare.reforge.core.content.internal.FactoryContentReader
import com.rustedwarfare.reforge.core.content.internal.PointDefenseContentReader
import com.rustedwarfare.reforge.core.content.internal.TurretContentReader
import com.rustedwarfare.reforge.core.content.internal.TmxReader
import com.rustedwarfare.reforge.core.content.internal.UnderwaterTargetContentReader
import java.io.IOException
import java.nio.file.Path

object ContentLoader {
    fun loadDropshipWithSources(path: Path): ContentResult<LoadedUnitContent> =
        load(path) { com.rustedwarfare.reforge.core.content.internal.DropshipContentReader.readWithSources(it) {} }
    /** Composes checked immutable declarations without touching the filesystem or renderer. */
    fun composeContentPacks(packs: List<LoadedContentPack>): ContentResult<LoadedContentSet> =
        load(packs.firstOrNull()?.manifest ?: Path.of(".")) { errorPath ->
            com.rustedwarfare.reforge.core.content.internal.ContentPackComposer.compose(packs, errorPath)
        }

    fun loadContentPacks(paths: List<Path>): ContentResult<LoadedContentSet> =
        load(paths.firstOrNull() ?: Path.of(".")) { errorPath ->
            val packs = paths.map { com.rustedwarfare.reforge.core.content.internal.ContentPackReader.read(it.toAbsolutePath().normalize()) }
            val combined = com.rustedwarfare.reforge.core.content.internal.ContentPackComposer.compose(packs, errorPath)
            when (val stable = combined.verifyStable()) {
                is ContentResult.Failure -> throw ContentReadException(stable.error)
                is ContentResult.Success -> Unit
            }
            combined
        }
    fun loadContentPack(path: Path): ContentResult<LoadedContentPack> =
        load(path, com.rustedwarfare.reforge.core.content.internal.ContentPackReader::read)
    fun loadEconomyUnit(path: Path): ContentResult<UnitDefinition> = load(path, com.rustedwarfare.reforge.core.content.internal.EconomyContentReader::read)
    fun loadEconomyUnit(path: Path, enableUpgrades: Boolean): ContentResult<UnitDefinition> =
        load(path) { com.rustedwarfare.reforge.core.content.internal.EconomyContentReader.read(it, enableUpgrades) }
    fun loadEconomyUnitWithSources(path: Path, enableUpgrades: Boolean = false): ContentResult<LoadedUnitContent> =
        load(path) { com.rustedwarfare.reforge.core.content.internal.EconomyContentReader.readWithSources(it, enableUpgrades) }
    fun loadCommandCenter(path: Path): ContentResult<UnitDefinition> = load(path, CommandCenterContentReader::read)
    fun loadCommandCenterWithSources(path: Path): ContentResult<LoadedUnitContent> = load(path, CommandCenterContentReader::readWithSources)
    fun loadBuilder(path: Path): ContentResult<UnitDefinition> = load(path, BuilderContentReader::read)
    fun loadBuilderWithSources(path: Path): ContentResult<LoadedUnitContent> = load(path, BuilderContentReader::readWithSources)

    fun loadPointDefense(path: Path): ContentResult<UnitDefinition> = load(path, PointDefenseContentReader::read)

    fun loadPointDefense(path: Path, enableConstruction: Boolean): ContentResult<UnitDefinition> =
        load(path) { PointDefenseContentReader.read(it, enableConstruction) }

    fun loadPointDefenseWithSources(path: Path, enableConstruction: Boolean = false): ContentResult<LoadedUnitContent> =
        load(path) { PointDefenseContentReader.readWithSources(it, enableConstruction) }

    fun loadTurretUnit(path: Path): ContentResult<UnitDefinition> = loadTurretUnit(path, false)

    fun loadTurretUnit(path: Path, enableUpgrades: Boolean): ContentResult<UnitDefinition> =
        load(path) { TurretContentReader.read(it, enableUpgrades) }

    fun loadTurretUnit(path: Path, enableUpgrades: Boolean, enableConstruction: Boolean): ContentResult<UnitDefinition> =
        load(path) { TurretContentReader.read(it, enableUpgrades, enableConstruction) }

    fun loadTurretUnitWithSources(path: Path, enableUpgrades: Boolean = false,
                                 enableConstruction: Boolean = false): ContentResult<LoadedUnitContent> =
        load(path) { TurretContentReader.readWithSources(it, enableUpgrades, enableConstruction) }

    fun loadFactory(path: Path): ContentResult<UnitDefinition> = load(path, FactoryContentReader::read)
    fun loadFactoryWithSources(path: Path): ContentResult<LoadedUnitContent> = load(path, FactoryContentReader::readWithSources)

    fun loadCombatUnit(iniPath: Path): ContentResult<UnitDefinition> = load(iniPath, CombatContentReader::read)

    fun loadCombatUnitWithSources(path: Path): ContentResult<LoadedUnitContent> = load(path, CombatContentReader::readWithSources)

    /** The finite two-legged mech schema shares the ordinary content/source reader. */
    fun loadMechUnit(path: Path): ContentResult<UnitDefinition> =
        load(path) { CombatContentReader.readMechWithSources(it).definition }

    fun loadHelicopterUnit(path: Path): ContentResult<UnitDefinition> =
        load(path) { CombatContentReader.readHelicopterWithSources(it).definition }

    fun loadHoverTankWithSources(path: Path): ContentResult<LoadedUnitContent> =
        load(path) { CombatContentReader.readHoverTankWithSources(it) }

    fun loadHeavyMechUnit(path: Path): ContentResult<UnitDefinition> =
        load(path) { CombatContentReader.readHeavyMechWithSources(it).definition }

    fun loadBattleshipUnit(path: Path): ContentResult<UnitDefinition> =
        load(path) { CombatContentReader.readBattleshipWithSources(it).definition }

    /** Author surface combat shares the common syntax and retains its complete parsed source closure. */
    fun loadAuthorWaterUnitWithSources(path: Path): ContentResult<LoadedUnitContent> =
        load(path) { CombatContentReader.readAuthorWaterWithSources(it) }

    fun loadAuthorConstructorWithSources(path: Path): ContentResult<LoadedUnitContent> =
        load(path) { com.rustedwarfare.reforge.core.content.internal.AuthorConstructionContentReader.readConstructorWithSources(it) }

    fun loadAuthorBuildingWithSources(path: Path): ContentResult<LoadedUnitContent> =
        load(path) { com.rustedwarfare.reforge.core.content.internal.AuthorConstructionContentReader.readBuildingWithSources(it) }

    fun loadUnderwaterTargetUnit(path: Path): ContentResult<UnitDefinition> =
        load(path) { UnderwaterTargetContentReader.readWithSources(it) {}.definition }

    fun loadSubmarineWithSources(path: Path): ContentResult<LoadedUnitContent> =
        load(path) { com.rustedwarfare.reforge.core.content.internal.SubmarineContentReader.readWithSources(it) {} }

    fun loadAmphibiousJetWithSources(path: Path): ContentResult<LoadedUnitContent> =
        load(path) { com.rustedwarfare.reforge.core.content.internal.AmphibiousJetContentReader.readWithSources(it) {} }

    fun loadHovercraftWithSources(path: Path): ContentResult<LoadedUnitContent> =
        load(path) { com.rustedwarfare.reforge.core.content.internal.HovercraftContentReader.readWithSources(it) {} }

    fun loadDeclaredTankWithSources(path: Path): ContentResult<LoadedUnitContent> =
        load(path) { CombatContentReader.readDeclaredWithSources(it) }

    fun loadUnitVariant(iniPath: Path): ContentResult<UnitDefinition> = load(iniPath, IniReader::read)
    fun loadMap(tmxPath: Path): ContentResult<MapDefinition> = load(tmxPath, TmxReader::read)

    private fun <T> load(path: Path, read: (Path) -> T): ContentResult<T> {
        val absolute = path.toAbsolutePath().normalize()
        return try {
            ContentResult.Success(read(absolute))
        } catch (failure: ContentReadException) {
            ContentResult.Failure(failure.error)
        } catch (failure: IOException) {
            ContentResult.Failure(ContentError(ContentErrorCode.BAD_REFERENCE, absolute.toString(), reason = failure.message ?: "Cannot read input"))
        } catch (failure: SecurityException) {
            ContentResult.Failure(ContentError(ContentErrorCode.BAD_REFERENCE, absolute.toString(), reason = failure.message ?: "Input access denied"))
        }
    }
}
