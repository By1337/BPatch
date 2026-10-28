# BPatch

Упаковка нескольких версий JAR в базовый архив и патчи с изменёнными файлами. Java 16, Maven 3.9+.

## Модули

- `bpatch-maven-plugin` — разрешение зависимостей Maven и упаковка ресурсов.
- `bpatch-core` — создание патчей, перепаковка JAR и фильтры.
- `bpatch-runtime` — чтение и распаковка патчей без внешних зависимостей. Для приложения достаточно этого модуля.

## Сборка

```sh
mvn clean install
```

## Упаковка

```xml
<plugin>
    <groupId>dev.by1337.bpatch</groupId>
    <artifactId>bpatch-maven-plugin</artifactId>
    <version>1.0.0</version>
    <executions>
        <execution>
            <goals><goal>pack</goal></goals>
        </execution>
    </executions>
    <configuration>
        <jars>
            <jar>
                <artifact>org.ow2.asm:asm:9.9.1</artifact>
                <output>libraries/asm.jar</output>
            </jar>
        </jars>
        <patchSets>
            <patchSet>
                <output>libraries/bridges.zip</output>
                <excludeMetaInf>true</excludeMetaInf>
                <versions>
                    <version>
                        <id>1.0</id>
                        <artifact>com.example:bridge:1.0</artifact>
                    </version>
                    <version>
                        <id>1.1</id>
                        <artifact>com.example:bridge:1.1</artifact>
                    </version>
                </versions>
            </patchSet>
        </patchSets>
    </configuration>
</plugin>
```

Цель `pack` выполняется на фазе `process-resources` и пишет в `${project.build.outputDirectory}`. Результат попадает в итоговый JAR при обычной сборке. Артефакты разрешаются через Maven с настройками проекта и `settings.xml`, без транзитивных зависимостей.

| Параметр | По умолчанию | Назначение |
|---|---|---|
| `compression` | `STORED` | Без внутреннего сжатия; `DEFLATED` включает сжатие |
| `patchSet.mode` | `CHAIN` | Относительно предыдущей версии; `FROM_BASE` — относительно первой |
| `excludeMetaInf` | `false` | Удалить `META-INF/**` из исходных JAR |
| `includes`, `excludes` | пустые | Фильтры ресурсов: `*`, `**`, `?` |
| `skip` | `false` | Пропустить упаковку; также `-Dbpatch.skip=true` |

Версии обрабатываются в порядке конфигурации. Первая — база. Фильтры применяются до сравнения файлов. Для вложения в сжатый JAR используйте `STORED`, чтобы сжатие выполнялось внешним архивом. После изменения списка выходных ресурсов выполняйте `clean`, чтобы удалить старые файлы.

## Распаковка

Включите `bpatch-runtime` в JAR приложения:

```xml
<dependency>
    <groupId>dev.by1337.bpatch</groupId>
    <artifactId>bpatch-runtime</artifactId>
    <version>1.0.0</version>
</dependency>
```

```java
try (PatchArchive archive = PatchArchive.open(Path.of("bridges.zip"))) {
    var versions = archive.versions();
    archive.unpack("1.1", Path.of("bridge.jar"));
}
```

Класс: `dev.by1337.bpatch.PatchArchive`. `open(InputStream)` позволяет читать ресурс приложения; поток закрывается, временный файл удаляется при закрытии архива. `unpack` восстанавливает выбранную версию и проверяет SHA-256 каждого шага. Подключение полученного JAR к classpath выполняет приложение.

## CLI

```sh
java -jar bpatch-runtime/target/bpatch-runtime-1.0.0.jar list bridges.zip
java -jar bpatch-runtime/target/bpatch-runtime-1.0.0.jar unpack bridges.zip 1.1 bridge.jar
```

## Формат

Обычный ZIP с `index.properties` и каталогами `versions/N/`. База содержит все файлы, остальные версии — добавленные и изменённые файлы. Удаления отмечаются пустыми записями с суффиксом `.removed`; этот суффикс зарезервирован. Байткод хранится без преобразований.
