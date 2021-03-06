/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.io.parquet.read;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.io.IOConstants;
import org.apache.hadoop.hive.ql.io.parquet.convert.DataWritableRecordConverter;
import org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe;
import org.apache.hadoop.hive.ql.metadata.VirtualColumn;
import org.apache.hadoop.hive.serde2.ColumnProjectionUtils;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.ListTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.StructTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.util.StringUtils;

import parquet.hadoop.api.ReadSupport;
import parquet.io.api.RecordMaterializer;
import parquet.schema.GroupType;
import parquet.schema.MessageType;
import parquet.schema.OriginalType;
import parquet.schema.Type;
import parquet.schema.Type.Repetition;
import parquet.schema.Types;
import parquet.schema.PrimitiveType.PrimitiveTypeName;

/**
 *
 * A MapWritableReadSupport
 *
 * Manages the translation between Hive and Parquet
 *
 */
public class DataWritableReadSupport extends ReadSupport<ArrayWritable> {

  public static final String HIVE_TABLE_AS_PARQUET_SCHEMA = "HIVE_TABLE_SCHEMA";
  public static final String PARQUET_COLUMN_INDEX_ACCESS = "parquet.column.index.access";
  public static final String PARQUET_COLUMN_USE_PARTITION = "parquet.column.use.partition";
  private TypeInfo hiveTypeInfo;
  /**
   * From a string which columns names (including hive column), return a list
   * of string columns
   *
   * @param columns comma separated list of columns
   * @return list with virtual columns removed
   */
  private static List<String> getColumnNames(final String columns) {
    return (List<String>) VirtualColumn.
        removeVirtualColumns(StringUtils.getStringCollection(columns));
  }

  /**
   * Returns a list of TypeInfo objects from a string which contains column
   * types strings.
   *
   * @param types Comma separated list of types
   * @return A list of TypeInfo objects.
   */
  private static List<TypeInfo> getColumnTypes(final String types) {
    return TypeInfoUtils.getTypeInfosFromTypeString(types);
  }

  /**
   * Searchs for a fieldName into a parquet GroupType by ignoring string case.
   * GroupType#getType(String fieldName) is case sensitive, so we use this method.
   *
   * @param groupType Group of field types where to search for fieldName
   * @param fieldName The field what we are searching
   * @return The Type object of the field found; null otherwise.
   */
  private static Type getFieldTypeIgnoreCase(GroupType groupType, String fieldName) {
    for (Type type : groupType.getFields()) {
      if (type.getName().equalsIgnoreCase(fieldName)) {
        return type;
      }
    }

    return null;
  }

  /**
   * Searchs column names by name on a given Parquet schema, and returns its corresponded
   * Parquet schema types.
   *
   * @param schema Group schema where to search for column names.
   * @param colNames List of column names.
   * @param colTypes List of column types.
   * @return List of GroupType objects of projected columns.
   */
  private static List<Type> getProjectedGroupFields(GroupType schema, List<String> colNames, List<TypeInfo> colTypes) {
    List<Type> schemaTypes = new ArrayList<Type>();

    ListIterator<String> columnIterator = colNames.listIterator();
    while (columnIterator.hasNext()) {
      TypeInfo colType = colTypes.get(columnIterator.nextIndex());
      String colName = columnIterator.next();

      Type fieldType = getFieldTypeIgnoreCase(schema, colName);
      if (fieldType == null) {
        schemaTypes.add(Types.optional(PrimitiveTypeName.BINARY).named(colName));
      } else {
        schemaTypes.add(getProjectedType(colType, fieldType));
      }
    }

    return schemaTypes;
  }

  private static Type getProjectedType(TypeInfo colType, Type fieldType) {
    switch (colType.getCategory()) {
      case STRUCT:
        List<Type> groupFields = getProjectedGroupFields(
          fieldType.asGroupType(),
          ((StructTypeInfo) colType).getAllStructFieldNames(),
          ((StructTypeInfo) colType).getAllStructFieldTypeInfos()
        );

        Type[] typesArray = groupFields.toArray(new Type[0]);
        return Types.buildGroup(fieldType.getRepetition())
          .addFields(typesArray)
          .named(fieldType.getName());
      case LIST:
        TypeInfo elemType = ((ListTypeInfo) colType).getListElementTypeInfo();
        if (elemType.getCategory() == ObjectInspector.Category.STRUCT) {
          Type subFieldType = fieldType.asGroupType().getType(0);
          if (!subFieldType.isPrimitive()) {
            String subFieldName = subFieldType.getName();
            Text name = new Text(subFieldName);
            if (name.equals(ParquetHiveSerDe.ARRAY) || name.equals(ParquetHiveSerDe.LIST)) {
              subFieldType = new GroupType(Repetition.REPEATED, subFieldName,
                getProjectedType(elemType, subFieldType.asGroupType().getType(0)));
            } else {
              subFieldType = getProjectedType(elemType, subFieldType);
            }
            return Types.buildGroup(Repetition.OPTIONAL).as(OriginalType.LIST).addFields(
              subFieldType).named(fieldType.getName());
          }
        }
        break;
      default:
    }
    return fieldType;
  }

  /**
   * Searchs column names by name on a given Parquet message schema, and returns its projected
   * Parquet schema types.
   *
   * @param schema Message type schema where to search for column names.
   * @param colNames List of column names.
   * @param colTypes List of column types.
   * @return A MessageType object of projected columns.
   */
  private static MessageType getSchemaByName(MessageType schema, List<String> colNames, List<TypeInfo> colTypes) {
    List<Type> projectedFields = getProjectedGroupFields(schema, colNames, colTypes);
    Type[] typesArray = projectedFields.toArray(new Type[0]);

    return Types.buildMessage()
        .addFields(typesArray)
        .named(schema.getName());
  }

  /**
   * Searchs column names by index on a given Parquet file schema, and returns its corresponded
   * Parquet schema types.
   *
   * @param schema Message schema where to search for column names.
   * @param colNames List of column names.
   * @param colIndexes List of column indexes.
   * @return A MessageType object of the column names found.
   */
  private static MessageType getSchemaByIndex(MessageType schema, List<String> colNames, List<Integer> colIndexes) {
    List<Type> schemaTypes = new ArrayList<Type>();

    for (Integer i : colIndexes) {
      if (i < colNames.size()) {
        if (i < schema.getFieldCount()) {
          schemaTypes.add(schema.getType(i));
        } else {
          //prefixing with '_mask_' to ensure no conflict with named
          //columns in the file schema
          schemaTypes.add(Types.optional(PrimitiveTypeName.BINARY).named("_mask_" + colNames.get(i)));
        }
      }
    }

    return new MessageType(schema.getName(), schemaTypes);
  }

  /**
   * It creates the readContext for Parquet side with the requested schema during the init phase.
   *
   * @param configuration    needed to get the wanted columns
   * @param keyValueMetaData // unused
   * @param fileSchema       parquet file schema
   * @return the parquet ReadContext
   */
  @Override
  public ReadContext init(final Configuration configuration, final Map<String, String> keyValueMetaData, final MessageType fileSchema) {
    boolean usePartitionColumns = configuration.getBoolean(PARQUET_COLUMN_USE_PARTITION, false);

    String tableColumnNames = configuration.get(IOConstants.COLUMNS);
    String partitionColumnNames = configuration.get(IOConstants.PARTITION_COLUMNS);
    String columnNames = usePartitionColumns ? partitionColumnNames : tableColumnNames;
    Map<String, String> contextMetadata = new HashMap<String, String>();
    boolean indexAccess = configuration.getBoolean(PARQUET_COLUMN_INDEX_ACCESS, false);

    if (columnNames != null) {
      List<String> columnNamesList = getColumnNames(columnNames);
      String columnTypes = usePartitionColumns ?
          configuration.get(IOConstants.PARTITION_COLUMNS_TYPES) :
          configuration.get(IOConstants.COLUMNS_TYPES);
      List<TypeInfo> columnTypesList = getColumnTypes(columnTypes);

      // The reference schema can be either that of the table or that of the partition
      MessageType refSchema;
      if (indexAccess) {
        List<Integer> indexSequence = new ArrayList<Integer>();

        // Generates a sequence list of indexes
        for(int i = 0; i < columnNamesList.size(); i++) {
          indexSequence.add(i);
        }

        refSchema = getSchemaByIndex(fileSchema, columnNamesList, indexSequence);
      } else {

        refSchema = getSchemaByName(fileSchema, columnNamesList, columnTypesList);
      }

      contextMetadata.put(HIVE_TABLE_AS_PARQUET_SCHEMA, refSchema.toString());
      contextMetadata.put(PARQUET_COLUMN_INDEX_ACCESS, String.valueOf(indexAccess));
      this.hiveTypeInfo = TypeInfoFactory.getStructTypeInfo(columnNamesList, columnTypesList);

      List<Integer> tableIndexColumnsWanted = ColumnProjectionUtils.getReadColumnIDs(configuration);
      List<Integer> indexColumnsWanted = usePartitionColumns ?
          toPartitionIndexColumns(tableIndexColumnsWanted, partitionColumnNames, tableColumnNames) :
          tableIndexColumnsWanted;

      if (!ColumnProjectionUtils.isReadAllColumns(configuration) && !indexColumnsWanted.isEmpty()) {
        MessageType requestedSchemaByUser =
            getSchemaByIndex(refSchema, columnNamesList, indexColumnsWanted);
        return new ReadContext(requestedSchemaByUser, contextMetadata);
      } else {
        return new ReadContext(refSchema, contextMetadata);
      }
    } else {
      contextMetadata.put(HIVE_TABLE_AS_PARQUET_SCHEMA, fileSchema.toString());
      return new ReadContext(fileSchema, contextMetadata);
    }
  }

  /**
   * Converts column indices referencing the table schema into the corresponding column indices referencing the
   * partition schema..
   *
   * @param tableIndexColumnsWanted The list of selected column indices from the table schema
   * @param partitionColumns The ordered list of columns in the partition schema
   * @param tableColumns The ordered list of columns in the table schema.
   * @return The new list of indices that reference the partition schema.
   */
  private List<Integer> toPartitionIndexColumns(List<Integer> tableIndexColumnsWanted, String partitionColumns, String tableColumns) {
    List<String> tableColumnsList = getColumnNames(tableColumns);
    List<String> partitionColumnsList = getColumnNames(partitionColumns);

    Map<String, Integer> partitionColumnIndexByName = new HashMap<>();
    for (int i = 0; i < partitionColumnsList.size(); i++) {
      partitionColumnIndexByName.put(partitionColumnsList.get(i), i);
    }

    List<Integer> partitionIndexColumnsWanted = new ArrayList<>();
    for (int tableIdx : tableIndexColumnsWanted) {
      Integer partitionIdx = partitionColumnIndexByName.get(tableColumnsList.get(tableIdx));
      if (partitionIdx != null) {
        partitionIndexColumnsWanted.add(partitionIdx);
      }
    }
    return partitionIndexColumnsWanted;
  }

  /**
   *
   * It creates the hive read support to interpret data from parquet to hive
   *
   * @param configuration // unused
   * @param keyValueMetaData
   * @param fileSchema // unused
   * @param readContext containing the requested schema and the schema of the hive table
   * @return Record Materialize for Hive
   */
  @Override
  public RecordMaterializer<ArrayWritable> prepareForRead(final Configuration configuration,
      final Map<String, String> keyValueMetaData, final MessageType fileSchema,
          final parquet.hadoop.api.ReadSupport.ReadContext readContext) {
    final Map<String, String> metadata = readContext.getReadSupportMetadata();
    if (metadata == null) {
      throw new IllegalStateException("ReadContext not initialized properly. " +
        "Don't know the Hive Schema.");
    }
    String key = HiveConf.ConfVars.HIVE_PARQUET_TIMESTAMP_SKIP_CONVERSION.varname;
    if (!metadata.containsKey(key)) {
      metadata.put(key, String.valueOf(HiveConf.getBoolVar(
        configuration, HiveConf.ConfVars.HIVE_PARQUET_TIMESTAMP_SKIP_CONVERSION)));
    }
    return new DataWritableRecordConverter(readContext.getRequestedSchema(), metadata, hiveTypeInfo);
  }
}
