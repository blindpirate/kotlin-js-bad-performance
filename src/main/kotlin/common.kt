@file:Suppress("UNCHECKED_CAST", "EXPERIMENTAL_API_USAGE")

//import ConstantPoolType.*
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder.Companion.DECODE_DONE
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.js.JSON.parse

interface GameMap {
    val id: String

    /**
     * How many tiles in this map?
     */
    val size: GridSize

    /**
     * How large (in pixel) is every tile in this map?
     */
    val tileSize: PixelSize

    /**
     * The map size in pixel
     */
    val pixelSize: PixelSize
        get() = tileSize * size

    val rawTiles: List<List<RawGameMapTile>>
}

@Serializable
data class PixelSize(val width: Int, val height: Int) {
    constructor(another: PixelSize) : this(another.width, another.height)

    operator fun times(gridSize: GridSize) = PixelSize(width * gridSize.width, height * gridSize.height)
    operator fun div(gridSize: GridSize) = PixelSize(width / gridSize.width, height / gridSize.height)
    operator fun div(pixelSize: PixelSize) = GridSize(width / pixelSize.width, height / pixelSize.height)
}

@Serializable
data class GridSize constructor(val width: Int, val height: Int) {
    constructor(another: GridSize) : this(another.width, another.height)

    operator fun times(pixelSize: PixelSize) = PixelSize(width * pixelSize.width, height * pixelSize.height)
}

data class PixelCoordinate(val x: Int, val y: Int) {
    operator fun div(tileSize: PixelSize) = GridCoordinate(x / tileSize.width, y / tileSize.height)

    fun offset(offsetX: Int, offsetY: Int) = PixelCoordinate(x + offsetX, y + offsetY)
    operator fun minus(other: PixelCoordinate) = PixelCoordinate(x - other.x, y - other.y)
    operator fun plus(other: PixelCoordinate) = PixelCoordinate(x + other.x, y + other.y)
}

/**
 * The coordinate in map grid, for example, the top-left tile has coordinate (0,0)
 */
@Serializable
data class GridCoordinate(val x: Int, val y: Int) {
    //    operator fun times(tileSize: PixelSize) = PixelCoordinate(x * tileSize.width, y * tileSize.height)
    operator fun plus(other: GridCoordinate) = GridCoordinate(x + other.x, y + other.y)
    operator fun minus(other: GridCoordinate) = GridCoordinate(x - other.x, y - other.y)

    fun outOf(gridSize: GridSize) = x < 0 || y < 0 || x >= gridSize.width || y >= gridSize.height
}

interface Compressable {
    /**
     * First round: scan all raw entries
     */
    fun addToRawConstantPool(rawConstantPool: MutableMap<Any, Int>)

    /**
     * Final round: compress and add to final constant pool
     */
    fun addToFinalConstantPool(rawConstantPool: Map<Any, Int>, finalConstantPool: LinkedHashSet<ConstantPoolEntry>)
}

/**
 * A map has a json and corresponding tileset png file.
 * [id].json and [id-tileset].png
 */

@Serializable
data class RawGameMap(
    override val id: String,
    /**
     * How many tiles in this map?
     */
    override val size: GridSize,

    /**
     * How large (in pixel) is every tile in this map?
     */
    override val tileSize: PixelSize,
    override val rawTiles: List<List<RawGameMapTile>>
) : GameMap {
    fun compress(): CompressedGameMap {
        val rawConstantPool: MutableMap<Any, Int> = HashMap()
        val constantPool: LinkedHashSet<ConstantPoolEntry> = LinkedHashSet()
        val flattenedTiles = rawTiles.flatten()

        flattenedTiles.forEach {
            it.addToRawConstantPool(rawConstantPool)
        }
        flattenedTiles.forEach {
            it.addToFinalConstantPool(rawConstantPool, constantPool)
        }
        return CompressedGameMap(id, size, tileSize, constantPool.toList(), flattenedTiles.mapToConstantPoolIndex(rawConstantPool))
    }
}

fun MutableMap<Any, Int>.recordRawConstantPoolEntries(vararg entries: Any) = entries.forEach {
    get(it) ?: put(it, this.size + 1)
}

fun <T> List<T>.mapToConstantPoolIndex(rawConstantPool: Map<Any, Int>): List<Int> = map { rawConstantPool.getValue(it as Any) }


@Serializable
data class RawGameMapTile(
    val layers: List<RawGameMapTileLayer>,
    val blocker: Boolean
) : Compressable {
    override fun addToRawConstantPool(rawConstantPool: MutableMap<Any, Int>) {
        rawConstantPool.recordRawConstantPoolEntries(this)
        layers.forEach { it.addToRawConstantPool(rawConstantPool) }
    }

    override fun addToFinalConstantPool(rawConstantPool: Map<Any, Int>, finalConstantPool: LinkedHashSet<ConstantPoolEntry>) {
        finalConstantPool.add(GameTileConstantPoolEntry(layers.mapToConstantPoolIndex(rawConstantPool), blocker))
        layers.forEach { it.addToFinalConstantPool(rawConstantPool, finalConstantPool) }
    }
}


val rawGameMapTileLayerModule = SerializersModule {
    polymorphic(RawGameMapTileLayer::class) {
        subclass(RawStaticImageLayer::class)
        subclass(RawAnimationLayer::class)
    }
}

@Polymorphic

interface RawGameMapTileLayer : Compressable {
    val abovePlayer: Boolean
}

@Serializable

data class RawStaticImageLayer(
    val coordinate: GridCoordinate,
    override val abovePlayer: Boolean
) : RawGameMapTileLayer {
    override fun addToRawConstantPool(rawConstantPool: MutableMap<Any, Int>) {
        rawConstantPool.recordRawConstantPoolEntries(this)
        rawConstantPool.recordRawConstantPoolEntries(coordinate)
    }

    override fun addToFinalConstantPool(rawConstantPool: Map<Any, Int>, finalConstantPool: LinkedHashSet<ConstantPoolEntry>) {
        finalConstantPool.add(StaticImageLayerEntry(rawConstantPool.getValue(coordinate), abovePlayer))
        finalConstantPool.add(CoordinateConstantPoolEntry(coordinate))
    }
}

@Serializable
data class RawAnimationLayer(
    val frames: List<RawTileAnimationFrame>,
    override val abovePlayer: Boolean
) : RawGameMapTileLayer {
    override fun addToRawConstantPool(rawConstantPool: MutableMap<Any, Int>) {
        rawConstantPool.recordRawConstantPoolEntries(this)
        frames.forEach { rawConstantPool.recordRawConstantPoolEntries(it) }
    }

    override fun addToFinalConstantPool(rawConstantPool: Map<Any, Int>, finalConstantPool: LinkedHashSet<ConstantPoolEntry>) {
        finalConstantPool.add(AnimationLayerEntry(frames.mapToConstantPoolIndex(rawConstantPool), abovePlayer))
        frames.forEach { finalConstantPool.add(AnimationFrameEntry(it)) }
    }
}

@Serializable
data class RawTileAnimationFrame(
    val coordinate: GridCoordinate,
    val duration: Int
)


class JSCompressedGameMap {
    var id: String = ""
    var size: GridSize = GridSize(0, 0)
    var tileSize: PixelSize = PixelSize(0, 0)
    var constantPool: Array<Any> = emptyArray()
    var tiles: Array<Int> = emptyArray()

    fun toKotlinCompressedGameMap(): CompressedGameMap {
        val constantPool:List<ConstantPoolEntry> = constantPool.map {
            val type = ConstantPoolType.ofIndex(it.asDynamic().type as Int)
            val valueJson = kotlin.js.JSON.stringify(it.asDynamic().value)
            val value = Json.decodeFromString(type.serializer, valueJson)
            type.of(value)
        }
        return CompressedGameMap(
            id,
            size,
            tileSize,
            constantPool,
            tiles.toList()
        )
    }
}

fun fromJSON(json: String): CompressedGameMap {
    val jsCompressedGameMap: dynamic = parse(json)

    val constantPool:List<ConstantPoolEntry> = (jsCompressedGameMap.constantPool as Array<dynamic>).map {
        val type = ConstantPoolType.ofIndex(it.type as Int)
        val valueJson = kotlin.js.JSON.stringify(it.value)
        val value = Json.decodeFromString(type.serializer, valueJson)
        type.of(value)
    }
    return CompressedGameMap(
        jsCompressedGameMap.id as String,
        GridSize(jsCompressedGameMap.size.width as Int, jsCompressedGameMap.size.height as Int),
        PixelSize(jsCompressedGameMap.tileSize.width as Int, jsCompressedGameMap.tileSize.height as Int),
        constantPool,
        (jsCompressedGameMap.tiles as Array<Int>).toList()
    )
}


@Serializable
data class CompressedGameMap(
    override val id: String,
    override val size: GridSize,
    override val tileSize: PixelSize,
    @Serializable(with = ConstantPoolEntryListSerializer::class)
    val constantPool: List<ConstantPoolEntry>,
    val tiles: List<Int>
) : GameMap {
    val rawGameMap: RawGameMap by lazy {
        decompress()
    }
    override val rawTiles: List<List<RawGameMapTile>>
        get() = rawGameMap.rawTiles

    fun decompress(): RawGameMap {
        val constantPoolTable: Map<Int, ConstantPoolEntry> = constantPool.withIndex().map {
            it.index + 1 to IndexedConstantPoolEntryWrapper(it.index + 1, it.value)
        }.toMap()

        val decompressedTiles = tiles.chunked(size.width)
            .map { it -> constantPoolTable.getValue(it).decompress(constantPoolTable) as RawGameMapTile }
        return RawGameMap(id, size, tileSize, decompressedTiles)
    }
}

inline fun <T, reified R> List<List<T>>.map(fn: (T) -> R): List<List<R>> {
    val tmp: MutableList<List<R>> = mutableListOf()

    forEach { row ->
        tmp.add(row.map(fn).toList())
    }
    return tmp.toList()
}

object ConstantPoolEntryListSerializer : JsonTransformingSerializer<List<ConstantPoolEntry>>(ListSerializer(ConstantPoolEntrySerializer))

@Suppress("UNCHECKED_CAST")
enum class ConstantPoolType(
    /**
     * The index in constant pool, starting from 1.
     */
    val index: Int,
    val serializer: KSerializer<Any>
) {
    Coordinate(1, GridCoordinate.serializer() as KSerializer<Any>) {
        override fun of(value: Any) = CoordinateConstantPoolEntry(value as GridCoordinate)
    },
    BlockerGameTile(2, ListSerializer(Int.serializer()) as KSerializer<Any>) {
        override fun of(value: Any) = GameTileConstantPoolEntry(value as List<Int>, true)
    },
    NonBlockerGameTile(3, ListSerializer(Int.serializer()) as KSerializer<Any>) {
        override fun of(value: Any) = GameTileConstantPoolEntry(value as List<Int>, false)
    },
    StaticImageAbovePlayer(4, Int.serializer() as KSerializer<Any>) {
        override fun of(value: Any) = StaticImageLayerEntry(value as Int, true)
    },
    AnimationAbovePlayer(5, ListSerializer(Int.serializer()) as KSerializer<Any>) {
        override fun of(value: Any) = AnimationLayerEntry(value as List<Int>, true)
    },
    StaticImageBelowPlayer(6, Int.serializer() as KSerializer<Any>) {
        override fun of(value: Any) = StaticImageLayerEntry(value as Int, false)
    },
    AnimationBelowPlayer(7, ListSerializer(Int.serializer()) as KSerializer<Any>) {
        override fun of(value: Any) = AnimationLayerEntry(value as List<Int>, false)
    },
    AnimationFrame(8, RawTileAnimationFrame.serializer() as KSerializer<Any>) {
        override fun of(value: Any): ConstantPoolEntry = AnimationFrameEntry(value as RawTileAnimationFrame)
    };

    abstract fun of(value: Any): ConstantPoolEntry

    companion object {
        fun ofIndex(index: Int) = values()[index - 1]
    }
}

// https://github.com/Kotlin/kotlinx.serialization/blob/master/guide/example/example-serializer-11.kt

object ConstantPoolEntrySerializer : KSerializer<ConstantPoolEntry> {
    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("ConstantPoolEntry", StructureKind.OBJECT) {
        element<Int>("type")
        element("value", buildSerialDescriptor("ConstantPoolEntryValue", PolymorphicKind.OPEN))
    }

    override fun deserialize(decoder: Decoder): ConstantPoolEntry {
        val composite = decoder.beginStructure(descriptor)
        var type: ConstantPoolType? = null
        var value: Any? = null
        while (true) {
            when (val index = composite.decodeElementIndex(descriptor)) {
                0 -> type = ConstantPoolType.ofIndex(composite.decodeIntElement(descriptor, 0))
                1 -> value = composite.decodeSerializableElement(descriptor, index, type!!.serializer)
                DECODE_DONE -> break
                else -> error("Unexpected index: $index")
            }
        }
        composite.endStructure(descriptor)
        return type!!.of(value!!)
    }


    override fun serialize(encoder: Encoder, value: ConstantPoolEntry) {
        val composite = encoder.beginStructure(descriptor)
        composite.encodeIntElement(descriptor, 0, value.type.index)
        composite.encodeSerializableElement(descriptor, 1, value.type.serializer, value.value)
        composite.endStructure(descriptor)
    }
}


@Serializable(with = ConstantPoolEntrySerializer::class)
interface ConstantPoolEntry {
    fun getIndex(): Int = -1
    val type: ConstantPoolType
    val value: Any

    fun <T> decompress(constantPoolTable: Map<Int, ConstantPoolEntry>): T
}


data class GameTileConstantPoolEntry(
    val layers: List<Int>,
    val blocker: Boolean
) : ConstantPoolEntry {
    override val type: ConstantPoolType
        get() = if (blocker) ConstantPoolType.BlockerGameTile else ConstantPoolType.NonBlockerGameTile
    override val value: Any
        get() = layers

    override fun <T> decompress(constantPoolTable: Map<Int, ConstantPoolEntry>): T {
        val layers = layers.map { constantPoolTable.getValue(it).decompress(constantPoolTable) as RawGameMapTileLayer }
        return RawGameMapTile(layers, blocker) as T
    }
}


class IndexedConstantPoolEntryWrapper(private val index: Int, private val delegate: ConstantPoolEntry) : ConstantPoolEntry by delegate {
    override fun getIndex() = index
}


data class CoordinateConstantPoolEntry(
    val coordinate: GridCoordinate
) : ConstantPoolEntry {
    override val type: ConstantPoolType
        get() = ConstantPoolType.Coordinate
    override val value: Any
        get() = coordinate

    override fun <T> decompress(constantPoolTable: Map<Int, ConstantPoolEntry>): T = coordinate as T
}


data class StaticImageLayerEntry(
    val coordinate: Int,
    val abovePlayer: Boolean
) : ConstantPoolEntry {
    override val type: ConstantPoolType
        get() = if (abovePlayer) ConstantPoolType.StaticImageAbovePlayer else ConstantPoolType.StaticImageBelowPlayer
    override val value: Any
        get() = coordinate

    override fun <T> decompress(constantPoolTable: Map<Int, ConstantPoolEntry>): T {
        return RawStaticImageLayer(constantPoolTable.getValue(coordinate).decompress(constantPoolTable) as GridCoordinate, abovePlayer) as T
    }
}


data class AnimationLayerEntry(
    val frames: List<Int>,
    val abovePlayer: Boolean
) : ConstantPoolEntry {
    override val type: ConstantPoolType
        get() = if (abovePlayer) ConstantPoolType.AnimationAbovePlayer else ConstantPoolType.AnimationBelowPlayer
    override val value: Any
        get() = frames

    override fun <T> decompress(constantPoolTable: Map<Int, ConstantPoolEntry>): T {
        val frames = frames.map { constantPoolTable.getValue(it).decompress(constantPoolTable) as RawTileAnimationFrame }
        return RawAnimationLayer(frames, abovePlayer) as T
    }
}

data class AnimationFrameEntry(
    val frame: RawTileAnimationFrame
) : ConstantPoolEntry {
    override val type: ConstantPoolType
        get() = ConstantPoolType.AnimationFrame
    override val value: Any
        get() = frame

    override fun <T> decompress(constantPoolTable: Map<Int, ConstantPoolEntry>): T = frame as T
}