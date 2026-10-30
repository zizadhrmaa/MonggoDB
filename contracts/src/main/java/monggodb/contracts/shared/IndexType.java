package monggodb.contracts.shared;

/**
 * Jenis struktur index yang didukung SM
 * Digunakan oleh QO untuk memilih jenis index yang akan digunakan untuk query
 */

public enum IndexType {
    /**
     * B+Tree untuk mendukung range query & pembacaan terurut
     */
    BTREE,

    /**
     * Hash untuk mendukung pencarian equality
     */
    HASH
}