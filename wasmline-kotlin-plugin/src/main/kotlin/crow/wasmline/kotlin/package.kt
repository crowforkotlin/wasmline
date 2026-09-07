package crow.wasmline.kotlin

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Represents a package name without an associated class. */
@JvmInline
internal value class FqPackageName(val fqName: FqName)

internal fun FqPackageName(name: String): FqPackageName = FqPackageName(FqName(name))

internal fun FqPackageName.classId(name: String): ClassId = ClassId(fqName, Name.identifier(name))

internal fun FqPackageName.callableId(name: String): CallableId = CallableId(fqName, Name.identifier(name))

internal fun ClassId.callableId(name: String): CallableId = CallableId(this, Name.identifier(name))
