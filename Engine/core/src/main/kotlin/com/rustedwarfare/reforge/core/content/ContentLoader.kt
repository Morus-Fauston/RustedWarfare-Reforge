package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.content.internal.IniReader
import com.rustedwarfare.reforge.core.content.internal.BuilderContentReader
import com.rustedwarfare.reforge.core.content.internal.CommandCenterContentReader
import com.rustedwarfare.reforge.core.content.internal.CombatContentReader
import com.rustedwarfare.reforge.core.content.internal.FactoryContentReader
import com.rustedwarfare.reforge.core.content.internal.PointDefenseContentReader
import com.rustedwarfare.reforge.core.content.internal.TurretContentReader
import com.rustedwarfare.reforge.core.content.internal.TmxReader
import java.io.IOException
import java.nio.file.Path

object ContentLoader {
    fun loadCommandCenter(path: Path): ContentResult<UnitDefinition> = load(path, CommandCenterContentReader::read)
    fun loadBuilder(path: Path): ContentResult<UnitDefinition> = load(path, BuilderContentReader::read)

    fun loadPointDefense(path: Path): ContentResult<UnitDefinition> = load(path, PointDefenseContentReader::read)

    fun loadTurretUnit(path: Path): ContentResult<UnitDefinition> = loadTurretUnit(path, false)

    fun loadTurretUnit(path: Path, enableUpgrades: Boolean): ContentResult<UnitDefinition> =
        load(path) { TurretContentReader.read(it, enableUpgrades) }

    fun loadFactory(path: Path): ContentResult<UnitDefinition> = load(path, FactoryContentReader::read)

    fun loadCombatUnit(iniPath: Path): ContentResult<UnitDefinition> = load(iniPath, CombatContentReader::read)

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
