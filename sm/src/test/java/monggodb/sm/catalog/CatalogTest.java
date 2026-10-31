package monggodb.sm.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import monggodb.contracts.shared.Column;
import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.ForeignKeyRef;
import monggodb.contracts.shared.IndexInfo;
import monggodb.contracts.shared.IndexType;
import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.TableStats;
import monggodb.contracts.shared.Value;

/**
 * Pengujian mandiri komponen CatalogManager (SM4).
 * Memverifikasi loadCatalog, saveCatalog (atomic write), manajemen skema, index, dan statistik.
 */
public final class CatalogTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== Menjalankan Pengujian SM4 CatalogManager ===");
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("sm4-catalog-test");

            testLoadCatalogNonExistent(tempDir.resolve("empty_db"));
            testSaveCatalogAtomicAndReload(tempDir.resolve("atomic_db"));
            testRegisterDuplicateTable(tempDir.resolve("dup_db"));
            testForeignKeyValidation(tempDir.resolve("fk_db"));
            testDropTableCascadeAndRestrict(tempDir.resolve("drop_db"));
            testRegisterAndDropIndex(tempDir.resolve("idx_db"));
            testMalformedJson(tempDir.resolve("bad_db"));
            testStatsPersistence(tempDir.resolve("stats_db"));

        } catch (Exception e) {
            System.err.println("Error tidak terduga saat pengujian: " + e.getMessage());
            e.printStackTrace();
            failed++;
        } finally {
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }

        System.out.println();
        System.out.println("================ RINGKASAN ================");
        System.out.printf("Total: %d lulus, %d gagal%n", passed, failed);
        System.out.println("===========================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testLoadCatalogNonExistent(Path dbDir) {
        try {
            CatalogManager cm = new CatalogManagerImpl();
            cm.loadCatalog(dbDir);
            check("loadCatalog pada folder kosong menghasilkan tabel kosong", cm.listTables().isEmpty());
            check("Folder data dibuat jika belum ada", Files.exists(dbDir));
        } catch (Exception e) {
            fail("testLoadCatalogNonExistent", e);
        }
    }

    private static void testSaveCatalogAtomicAndReload(Path dbDir) {
        try {
            CatalogManager cm1 = new CatalogManagerImpl();
            cm1.loadCatalog(dbDir);

            Schema schema = new Schema("students", List.of(
                    new Column("nim", "students", DataType.INTEGER, 0, true, true, null),
                    new Column("name", "students", DataType.VARCHAR, 50, false, false, null),
                    new Column("gpa", "students", DataType.FLOAT, 0, false, false, null)));
            cm1.registerTable(schema);

            Path catalogFile = dbDir.resolve("catalog.json");
            Path tempFile = dbDir.resolve("catalog.json.tmp");
            check("catalog.json harus ada di disk setelah registerTable", Files.exists(catalogFile));
            check("catalog.json.tmp tidak boleh tertinggal setelah atomic rename", !Files.exists(tempFile));

            // Reload dari disk menggunakan instance CatalogManager baru
            CatalogManager cm2 = new CatalogManagerImpl();
            cm2.loadCatalog(dbDir);

            check("Tabel 'students' ditemukan setelah reload", cm2.hasTable("students"));
            check("Pencarian nama tabel case-insensitive", cm2.hasTable("STUDENTS"));
            Schema loaded = cm2.getTableSchema("students");
            check("Jumlah kolom cocok", loaded.size() == 3);
            check("Tipe kolom 0 adalah INTEGER", loaded.getColumns().get(0).getDataType() == DataType.INTEGER);
            check("Kolom 0 adalah primary key", loaded.getColumns().get(0).isPrimaryKey());
            check("Panjang kolom 1 adalah 50", loaded.getColumns().get(1).getLength() == 50);

        } catch (Exception e) {
            fail("testSaveCatalogAtomicAndReload", e);
        }
    }

    private static void testRegisterDuplicateTable(Path dbDir) {
        try {
            CatalogManager cm = new CatalogManagerImpl();
            cm.loadCatalog(dbDir);

            Schema s1 = new Schema("courses", List.of(
                    new Column("code", "courses", DataType.CHAR, 6, true, true, null)));
            cm.registerTable(s1);

            boolean thrown = false;
            try {
                Schema s2 = new Schema("COURSES", List.of(
                        new Column("code", "courses", DataType.CHAR, 6, true, true, null)));
                cm.registerTable(s2);
            } catch (StorageException e) {
                if (e.getErrorCode() == StorageException.Code.TABLE_ALREADY_EXISTS) {
                    thrown = true;
                }
            }
            check("Mendaftarkan tabel duplikat melempar TABLE_ALREADY_EXISTS", thrown);
        } catch (Exception e) {
            fail("testRegisterDuplicateTable", e);
        }
    }

    private static void testForeignKeyValidation(Path dbDir) {
        try {
            CatalogManager cm = new CatalogManagerImpl();
            cm.loadCatalog(dbDir);

            // 1. Foreign key merujuk tabel yang tidak ada
            boolean thrownNotFound = false;
            try {
                Schema s1 = new Schema("enrollments", List.of(
                        new Column("id", "enrollments", DataType.INTEGER, 0, true, true, null),
                        new Column("dept_id", "enrollments", DataType.INTEGER, 0, false, false,
                                new ForeignKeyRef("departments", "id"))));
                cm.registerTable(s1);
            } catch (StorageException e) {
                if (e.getErrorCode() == StorageException.Code.TABLE_NOT_FOUND) {
                    thrownNotFound = true;
                }
            }
            check("FK ke tabel yang belum ada melempar TABLE_NOT_FOUND", thrownNotFound);

            // 2. Buat tabel departments
            Schema dept = new Schema("departments", List.of(
                    new Column("id", "departments", DataType.INTEGER, 0, true, true, null),
                    new Column("name", "departments", DataType.VARCHAR, 100, false, false, null)));
            cm.registerTable(dept);

            // 3. FK ke kolom yang tidak ada pada tabel yang ada
            boolean thrownColNotFound = false;
            try {
                Schema s2 = new Schema("enrollments", List.of(
                        new Column("id", "enrollments", DataType.INTEGER, 0, true, true, null),
                        new Column("dept_id", "enrollments", DataType.INTEGER, 0, false, false,
                                new ForeignKeyRef("departments", "non_existent"))));
                cm.registerTable(s2);
            } catch (StorageException e) {
                if (e.getErrorCode() == StorageException.Code.COLUMN_NOT_FOUND) {
                    thrownColNotFound = true;
                }
            }
            check("FK ke kolom yang tidak ada melempar COLUMN_NOT_FOUND", thrownColNotFound);

            // 4. FK valid
            Schema sValid = new Schema("enrollments", List.of(
                    new Column("id", "enrollments", DataType.INTEGER, 0, true, true, null),
                    new Column("dept_id", "enrollments", DataType.INTEGER, 0, false, false,
                                new ForeignKeyRef("departments", "id"))));
            cm.registerTable(sValid);
            check("FK valid berhasil didaftarkan", cm.hasTable("enrollments"));

        } catch (Exception e) {
            fail("testForeignKeyValidation", e);
        }
    }

    private static void testDropTableCascadeAndRestrict(Path dbDir) {
        try {
            CatalogManager cm = new CatalogManagerImpl();
            cm.loadCatalog(dbDir);

            Schema parent = new Schema("users", List.of(
                    new Column("id", "users", DataType.INTEGER, 0, true, true, null)));
            cm.registerTable(parent);

            Schema child = new Schema("orders", List.of(
                    new Column("order_id", "orders", DataType.INTEGER, 0, true, true, null),
                    new Column("user_id", "orders", DataType.INTEGER, 0, false, false,
                            new ForeignKeyRef("users", "id"))));
            cm.registerTable(child);

            // Drop RESTRICT harus gagal
            boolean thrownRef = false;
            try {
                cm.dropTable("users", false);
            } catch (StorageException e) {
                if (e.getErrorCode() == StorageException.Code.TABLE_REFERENCED) {
                    thrownRef = true;
                }
            }
            check("dropTable RESTRICT melempar TABLE_REFERENCED jika masih dirujuk", thrownRef);
            check("Tabel users tetap ada setelah drop RESTRICT ditolak", cm.hasTable("users"));

            // Drop CASCADE harus berhasil menghapus child juga
            List<String> dropped = cm.dropTable("users", true);
            check("dropTable CASCADE menghapus users dan orders", !cm.hasTable("users") && !cm.hasTable("orders"));
            check("Daftar tabel terhapus berisi users dan orders", dropped.contains("users") && dropped.contains("orders"));

        } catch (Exception e) {
            fail("testDropTableCascadeAndRestrict", e);
        }
    }

    private static void testRegisterAndDropIndex(Path dbDir) {
        try {
            CatalogManager cm = new CatalogManagerImpl();
            cm.loadCatalog(dbDir);

            Schema schema = new Schema("products", List.of(
                    new Column("id", "products", DataType.INTEGER, 0, true, true, null),
                    new Column("sku", "products", DataType.VARCHAR, 20, false, false, null)));
            cm.registerTable(schema);

            IndexInfo idx = new IndexInfo("idx_products_sku", "products", "sku", IndexType.BTREE);
            cm.registerIndex(idx);

            check("Index terdaftar pada tabel", cm.getIndexes("products").size() == 1);
            check("Nama index sesuai", cm.getIndexes("products").get(0).getIndexName().equals("idx_products_sku"));

            // Duplikat index name
            boolean thrownDup = false;
            try {
                cm.registerIndex(new IndexInfo("idx_products_sku", "products", "id", IndexType.HASH));
            } catch (StorageException e) {
                if (e.getErrorCode() == StorageException.Code.INDEX_ALREADY_EXISTS) {
                    thrownDup = true;
                }
            }
            check("Index dengan nama duplikat melempar INDEX_ALREADY_EXISTS", thrownDup);

            // Drop index
            cm.dropIndex("products", "idx_products_sku");
            check("Index berhasil dihapus", cm.getIndexes("products").isEmpty());

        } catch (Exception e) {
            fail("testRegisterAndDropIndex", e);
        }
    }

    private static void testMalformedJson(Path dbDir) {
        try {
            Files.createDirectories(dbDir);
            Path catalogFile = dbDir.resolve("catalog.json");
            Files.writeString(catalogFile, "{ this is not valid json : [");

            CatalogManager cm = new CatalogManagerImpl();
            boolean thrown = false;
            try {
                cm.loadCatalog(dbDir);
            } catch (StorageException e) {
                if (e.getErrorCode() == StorageException.Code.MALFORMED_RECORD) {
                    thrown = true;
                }
            }
            check("loadCatalog melempar MALFORMED_RECORD untuk JSON rusak", thrown);
        } catch (Exception e) {
            fail("testMalformedJson", e);
        }
    }

    private static void testStatsPersistence(Path dbDir) {
        try {
            CatalogManager cm = new CatalogManagerImpl();
            cm.loadCatalog(dbDir);

            Schema schema = new Schema("metrics", List.of(
                    new Column("id", "metrics", DataType.INTEGER, 0, true, true, null)));
            cm.registerTable(schema);

            TableStats stats = cm.getStats("metrics");
            check("Statistik awal memiliki recordCount 0", stats.getRecordCount() == 0);

            // Perbarui statistik
            TableStats newStats = new TableStats("metrics", 100, 5, 25.0, 160.0, 4096, stats.getColumnStats());
            cm.updateStats("metrics", newStats);
            cm.saveCatalog();

            // Reload dari disk
            CatalogManager cm2 = new CatalogManagerImpl();
            cm2.loadCatalog(dbDir);
            TableStats loaded = cm2.getStats("metrics");
            check("Statistik berhasil di-load kembali dari disk", loaded.getRecordCount() == 100);
            check("Block count cocok", loaded.getBlockCount() == 5);

        } catch (Exception e) {
            fail("testStatsPersistence", e);
        }
    }

    private static void check(String desc, boolean condition) {
        if (condition) {
            System.out.printf("[LULUS] %s%n", desc);
            passed++;
        } else {
            System.out.printf("[GAGAL] %s%n", desc);
            failed++;
        }
    }

    private static void fail(String testName, Throwable t) {
        System.err.printf("[ERROR] %s: %s%n", testName, t.getMessage());
        t.printStackTrace();
        failed++;
    }

    private static void deleteRecursively(Path path) {
        try {
            if (Files.exists(path)) {
                Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } catch (IOException ignored) {
        }
    }
}

