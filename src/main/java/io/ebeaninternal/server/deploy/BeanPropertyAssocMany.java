package io.ebeaninternal.server.deploy;

import io.ebean.SqlUpdate;
import io.ebean.Transaction;
import io.ebean.bean.BeanCollection;
import io.ebean.bean.BeanCollection.ModifyListenMode;
import io.ebean.bean.BeanCollectionAdd;
import io.ebean.bean.BeanCollectionLoader;
import io.ebean.bean.EntityBean;
import io.ebean.text.PathProperties;
import io.ebeaninternal.api.SpiExpressionRequest;
import io.ebeaninternal.api.SpiQuery;
import io.ebeaninternal.server.deploy.id.ImportedId;
import io.ebeaninternal.server.deploy.meta.DeployBeanPropertyAssocMany;
import io.ebeaninternal.server.el.ElPropertyChainBuilder;
import io.ebeaninternal.server.el.ElPropertyValue;
import io.ebeaninternal.server.query.STreePropertyAssocMany;
import io.ebeaninternal.server.query.SqlBeanLoad;
import io.ebeaninternal.server.text.json.ReadJson;
import io.ebeaninternal.server.text.json.SpiJsonWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.PersistenceException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Property mapped to a List Set or Map.
 */
public class BeanPropertyAssocMany<T> extends BeanPropertyAssoc<T> implements STreePropertyAssocMany {

  private static final Logger logger = LoggerFactory.getLogger(BeanPropertyAssocMany.class);

  private final BeanPropertyAssocManyJsonHelp jsonHelp;

  /**
   * Join for manyToMany intersection table.
   */
  private final TableJoin intersectionJoin;
  private final String intersectionPublishTable;
  private final String intersectionDraftTable;

  /**
   * For ManyToMany this is the Inverse join used to build reference queries.
   */
  final TableJoin inverseJoin;

  /**
   * Flag to indicate that this is a unidirectional relationship.
   */
  private final boolean unidirectional;

  private final boolean o2mJoinTable;

  /**
   * Flag to indicate that the target has a order column to auto populate.
   */
  private final boolean hasOrderColumn;

  /**
   * Flag to indicate manyToMany relationship.
   */
  private final boolean manyToMany;

  private final boolean elementCollection;

  /**
   * Order by used when fetch joining the associated many.
   */
  private final String fetchOrderBy;

  /**
   * Order by used when lazy loading the associated many.
   */
  private String lazyFetchOrderBy;

  private final String mapKey;

  /**
   * The type of the many, set, list or map.
   */
  private final ManyType manyType;

  private final ModifyListenMode modifyListenMode;

  private BeanProperty mapKeyProperty;

  /**
   * Property on the 'child' bean that links back to the 'master'.
   */
  protected BeanPropertyAssocOne<?> childMasterProperty;

  private String childMasterIdProperty;

  private boolean embeddedExportedProperties;

  private BeanCollectionHelp<T> help;

  private ImportedId importedId;

  private BeanPropertyAssocManySqlHelp<T> sqlHelp;

  /**
   * Create this property.
   */
  public BeanPropertyAssocMany(BeanDescriptor<?> descriptor, DeployBeanPropertyAssocMany<T> deploy) {
    super(descriptor, deploy);
    this.unidirectional = deploy.isUnidirectional();
    this.o2mJoinTable = deploy.isO2mJoinTable();
    this.hasOrderColumn = deploy.hasOrderColumn();
    this.manyToMany = deploy.isManyToMany();
    this.elementCollection = deploy.isElementCollection();
    this.manyType = deploy.getManyType();
    this.mapKey = deploy.getMapKey();
    this.fetchOrderBy = deploy.getFetchOrderBy();
    this.intersectionJoin = deploy.createIntersectionTableJoin();
    if (intersectionJoin != null) {
      this.intersectionPublishTable = intersectionJoin.getTable();
      this.intersectionDraftTable = deploy.getIntersectionDraftTable();
    } else {
      this.intersectionPublishTable = null;
      this.intersectionDraftTable = null;
    }
    this.inverseJoin = deploy.createInverseTableJoin();
    this.modifyListenMode = deploy.getModifyListenMode();
    this.jsonHelp = new BeanPropertyAssocManyJsonHelp(this);
  }

  @Override
  public void initialise(BeanDescriptorInitContext initContext) {
    super.initialise(initContext);
    initialiseAssocMany();
  }

  private void initialiseAssocMany() {
    if (!isTransient) {
      this.help = BeanCollectionHelpFactory.create(this);
      if (hasJoinTable() || elementCollection) {
        importedId = createImportedId(this, targetDescriptor, tableJoin);

      } else {
        // find the property in the many that matches
        // back to the master (Order in the OrderDetail bean)
        childMasterProperty = initChildMasterProperty();
        if (childMasterProperty != null) {
          childMasterProperty.setRelationshipProperty(this);
        }
      }

      if (mapKey != null) {
        mapKeyProperty = initMapKeyProperty();
      }

      exportedProperties = createExported();
      this.sqlHelp = new BeanPropertyAssocManySqlHelp<>(this, exportedProperties);

      if (exportedProperties.length > 0) {
        embeddedExportedProperties = exportedProperties[0].isEmbedded();
        if (fetchOrderBy != null) {
          lazyFetchOrderBy = sqlHelp.lazyFetchOrderBy(fetchOrderBy);
        }
      }
    }
  }

  String targetTable() {
    return targetDescriptor.getBaseTable();
  }

  /**
   * Initialise after the target bean descriptors have been all set.
   */
  public void initialisePostTarget() {
    if (childMasterProperty != null) {
      BeanProperty masterId = childMasterProperty.getTargetDescriptor().getIdProperty();
      childMasterIdProperty = childMasterProperty.getName() + "." + masterId.getName();
    }
  }

  @Override
  protected void docStoreIncludeByDefault(PathProperties pathProps) {
    // by default not including "Many" properties in document store
  }

  /**
   * Return the underlying collection of beans.
   */
  public Collection getRawCollection(EntityBean bean) {
    return help.underlying(getVal(bean));
  }

  /**
   * Copy collection value if existing is empty.
   */
  @Override
  public void merge(EntityBean bean, EntityBean existing) {

    Object existingCollection = getVal(existing);
    if (existingCollection instanceof BeanCollection<?>) {
      BeanCollection<?> toBC = (BeanCollection<?>) existingCollection;
      if (!toBC.isPopulated()) {
        Object fromCollection = getVal(bean);
        if (fromCollection instanceof BeanCollection<?>) {
          BeanCollection<?> fromBC = (BeanCollection<?>) fromCollection;
          if (fromBC.isPopulated()) {
            toBC.loadFrom(fromBC);
          }
        }
      }
    }
  }

  /**
   * Add the bean to the appropriate collection on the parent bean.
   */
  public void addBeanToCollectionWithCreate(EntityBean parentBean, EntityBean detailBean, boolean withCheck) {
    BeanCollection<?> bc = (BeanCollection<?>) super.getValue(parentBean);
    if (bc == null) {
      bc = help.createEmpty(parentBean);
      setValue(parentBean, bc);
    }
    help.add(bc, detailBean, withCheck);
  }

  /**
   * Return true if this is considered 'empty' from a save perspective.
   */
  public boolean isSkipSaveBeanCollection(EntityBean bean, boolean insertedParent) {
    Object val = getValue(bean);
    if (val == null) {
      return true;
    }
    if ((val instanceof BeanCollection<?>)) {
      return ((BeanCollection<?>) val).isSkipSave();
    }
    if (insertedParent) {
      // check 'vanilla' collection types
      if (val instanceof Collection<?>) {
        return ((Collection<?>) val).isEmpty();
      }
      if (val instanceof Map<?, ?>) {
        return ((Map<?, ?>) val).isEmpty();
      }
    }
    return false;
  }

  /**
   * Reset the many properties to be empty and ready for reloading.
   * <p>
   * Used in bean refresh.
   */
  public void resetMany(EntityBean bean) {
    Object value = getValue(bean);
    if (value instanceof BeanCollection) {
      // reset the collection back to empty
      ((BeanCollection<?>) value).reset(bean, name);
    } else {
      createReference(bean);
    }
  }

  @Override
  public ElPropertyValue buildElPropertyValue(String propName, String remainder, ElPropertyChainBuilder chain, boolean propertyDeploy) {
    return createElPropertyValue(propName, remainder, chain, propertyDeploy);
  }

  @Override
  public void buildRawSqlSelectChain(String prefix, List<String> selectChain) {
    // do not add to the selectChain at the top level of the Many bean
  }

  public SqlUpdate deleteByParentId(Object parentId, List<Object> parentIdist) {
    if (parentId != null) {
      return sqlHelp.deleteByParentId(parentId);
    } else {
      return sqlHelp.deleteByParentIdList(parentIdist);
    }
  }

  /**
   * Find the Id's of detail beans given a parent Id or list of parent Id's.
   */
  public List<Object> findIdsByParentId(Object parentId, List<Object> parentIdList, Transaction t, List<Object> excludeDetailIds) {
    if (parentId != null) {
      return sqlHelp.findIdsByParentId(parentId, t, excludeDetailIds);
    } else {
      return sqlHelp.findIdsByParentIdList(parentIdList, t, excludeDetailIds);
    }
  }

  /**
   * Exclude many properties from bean cache data.
   */
  @Override
  public boolean isCacheDataInclude() {
    // this would change for DB Array type support
    return false;
  }

  /**
   * Add the loaded current bean to its associated parent.
   *
   * Helper method used by Elastic integration when loading with a persistence context.
   */
  public void lazyLoadMany(EntityBean current) {
    EntityBean parentBean = childMasterProperty.getValueAsEntityBean(current);
    if (parentBean != null) {
      addBeanToCollectionWithCreate(parentBean, current, true);
    }
  }

  public void addWhereParentIdIn(SpiQuery<?> query, List<Object> parentIds, boolean useDocStore) {
    if (useDocStore) {
      // assumes the ManyToOne property is included
      query.where().in(childMasterIdProperty, parentIds);
    } else {
      sqlHelp.addWhereParentIdIn(query, parentIds);
    }
  }

  /**
   * Set the lazy load server to help create reference collections (that lazy
   * load on demand).
   */
  public void setLoader(BeanCollectionLoader loader) {
    if (help != null) {
      help.setLoader(loader);
    }
  }

  /**
   * Return the mode for listening to modifications to collections for this
   * association.
   */
  public ModifyListenMode getModifyListenMode() {
    return modifyListenMode;
  }

  @Override
  public void appendSelect(DbSqlContext ctx, boolean subQuery) {
  }

  @Override
  public void loadIgnore(DbReadContext ctx) {
    // nothing to ignore for Many
  }

  @Override
  public void load(SqlBeanLoad sqlBeanLoad) {
    // do nothing, as a lazy loading BeanCollection 'reference'
    // is created and registered with the loading context
    // in SqlTreeNodeBean.createListProxies()
  }

  @Override
  public Object readSet(DbReadContext ctx, EntityBean bean) {
    return null;
  }

  @Override
  public Object read(DbReadContext ctx) throws SQLException {
    return null;
  }

  public void add(BeanCollection<?> collection, EntityBean bean) {
    help.add(collection, bean, false);
  }

  @Override
  public String getAssocIsEmpty(SpiExpressionRequest request, String path) {

    boolean softDelete = targetDescriptor.isSoftDelete();

    StringBuilder sb = new StringBuilder(50);
    SpiQuery<?> query = request.getQueryRequest().getQuery();
    if (hasJoinTable()) {
      sb.append(query.isAsDraft() ? intersectionDraftTable : intersectionPublishTable);
    } else {
      sb.append(targetDescriptor.getBaseTable(query.getTemporalMode()));
    }
    if (softDelete && hasJoinTable()) {
      sb.append(" x join ");
      sb.append(targetDescriptor.getBaseTable(query.getTemporalMode()));
      sb.append(" x2 on ");
      inverseJoin.addJoin("x2", "x", sb);
    } else {
      sb.append(" x");
    }

    sb.append(" where ");
    for (int i = 0; i < exportedProperties.length; i++) {
      if (i > 0) {
        sb.append(" and ");
      }
      exportedProperties[i].appendWhere(sb, "x.", path);
    }
    if (softDelete) {
      String alias = hasJoinTable() ? "x2" : "x";
      sb.append(" and ").append(targetDescriptor.getSoftDeletePredicate(alias));
    }
    return sb.toString();
  }

  /**
   * Return the Id values from the given bean.
   */
  @Override
  public Object[] getAssocIdValues(EntityBean bean) {
    return targetDescriptor.getIdBinder().getIdValues(bean);
  }

  /**
   * Return the Id expression to add to where clause etc.
   */
  @Override
  public String getAssocIdExpression(String prefix, String operator) {
    return targetDescriptor.getIdBinder().getAssocOneIdExpr(prefix, operator);
  }

  /**
   * Return the logical id value expression taking into account embedded id's.
   */
  @Override
  public String getAssocIdInValueExpr(boolean not, int size) {
    return targetDescriptor.getIdBinder().getIdInValueExpr(not, size);
  }

  /**
   * Return the logical id in expression taking into account embedded id's.
   */
  @Override
  public String getAssocIdInExpr(String prefix) {
    return targetDescriptor.getIdBinder().getAssocIdInExpr(prefix);
  }

  @Override
  public boolean isMany() {
    return true;
  }

  public boolean hasOrderColumn() {
    return hasOrderColumn;
  }

  @Override
  public boolean isAssocMany() {
    return true;
  }

  @Override
  public boolean isAssocId() {
    return true;
  }

  @Override
  public boolean isAssocProperty() {
    return true;
  }

  /**
   * Returns true.
   */
  @Override
  public boolean containsMany() {
    return true;
  }

  /**
   * Return the many type.
   */
  public ManyType getManyType() {
    return manyType;
  }

  /**
   * Return true if this is many to many.
   */
  public boolean hasJoinTable() {
    return manyToMany || o2mJoinTable;
  }

  /**
   * Return true if this is a one to many with a join table.
   */
  public boolean isO2mJoinTable() {
    return o2mJoinTable;
  }

  /**
   * Return true if this is many to many.
   */
  public boolean isManyToMany() {
    return manyToMany;
  }

  public boolean isElementCollection() {
    return elementCollection;
  }

  /**
   * ManyToMany only, join from local table to intersection table.
   */
  public TableJoin getIntersectionTableJoin() {
    return intersectionJoin;
  }

  /**
   * Set the join properties from the parent bean to the child bean.
   * This is only valid for OneToMany and NOT valid for ManyToMany.
   */
  public void setJoinValuesToChild(EntityBean parent, EntityBean child, Object mapKeyValue) {

    if (mapKeyProperty != null) {
      mapKeyProperty.setValue(child, mapKeyValue);
    }

    if (!manyToMany && childMasterProperty != null) {
      // bidirectional in the sense that the 'master' property
      // exists on the 'detail' bean
      childMasterProperty.setValue(child, parent);
    }
  }

  /**
   * Return the order by clause used to order the fetching of the data for
   * this list, set or map.
   */
  public String getFetchOrderBy() {
    return fetchOrderBy;
  }

  /**
   * Return the order by for use when lazy loading the associated collection.
   */
  public String getLazyFetchOrderBy() {
    return lazyFetchOrderBy;
  }

  /**
   * Return the default mapKey when returning a Map.
   */
  public String getMapKey() {
    return mapKey;
  }

  public BeanCollection<?> createReferenceIfNull(EntityBean parentBean) {

    Object v = getValue(parentBean);
    if (v instanceof BeanCollection<?>) {
      BeanCollection<?> bc = (BeanCollection<?>) v;
      return bc.isReference() ? bc : null;
    } else if (v != null) {
      return null;
    } else {
      return createReference(parentBean);
    }
  }

  public BeanCollection<?> createReference(EntityBean parentBean) {

    BeanCollection<?> ref = help.createReference(parentBean);
    setValue(parentBean, ref);
    return ref;
  }

  public BeanCollection<T> createEmpty(EntityBean parentBean) {
    return help.createEmpty(parentBean);
  }

  public BeanCollectionAdd getBeanCollectionAdd(Object bc, String mapKey) {
    return help.getBeanCollectionAdd(bc, mapKey);
  }

  public Object getParentId(EntityBean parentBean) {
    return descriptor.getId(parentBean);
  }

  public void addSelectExported(DbSqlContext ctx, String tableAlias) {

    String alias = hasJoinTable() ? "int_" : tableAlias;
    if (alias == null) {
      alias = "t0";
    }
    for (ExportedProperty exportedProperty : exportedProperties) {
      ctx.appendColumn(alias, exportedProperty.getForeignDbColumn());
    }
  }

  /**
   * Create the array of ExportedProperty used to build reference objects.
   */
  private ExportedProperty[] createExported() {

    BeanProperty idProp = descriptor.getIdProperty();

    ArrayList<ExportedProperty> list = new ArrayList<>();

    if (idProp != null && idProp.isEmbedded()) {

      BeanPropertyAssocOne<?> one = (BeanPropertyAssocOne<?>) idProp;
      try {
        for (BeanProperty emId : one.getTargetDescriptor().propertiesBaseScalar()) {
          list.add(findMatch(true, emId));
        }
      } catch (PersistenceException e) {
        // not found as individual scalar properties
        logger.error("Could not find a exported property?", e);
      }

    } else {
      if (idProp != null) {
        list.add(findMatch(false, idProp));
      }
    }

    return list.toArray(new ExportedProperty[list.size()]);
  }

  /**
   * Find the matching foreignDbColumn for a given local property.
   */
  private ExportedProperty findMatch(boolean embedded, BeanProperty prop) {

    if (hasJoinTable()) {
      // look for column going to intersection
      return findMatch(embedded, prop, prop.getDbColumn(), intersectionJoin);
    } else {
      return findMatch(embedded, prop, prop.getDbColumn(), tableJoin);
    }
  }

  /**
   * Return the child property that links back to the master bean.
   * <p>
   * Note that childMasterProperty will be null if a field is used instead of
   * a ManyToOne bean association.
   * </p>
   */
  private BeanPropertyAssocOne<?> initChildMasterProperty() {

    if (unidirectional) {
      return null;
    }

    // search for the property, to see if it exists
    Class<?> beanType = descriptor.getBeanType();
    BeanDescriptor<?> targetDesc = getTargetDescriptor();

    for (BeanPropertyAssocOne<?> prop : targetDesc.propertiesOne()) {
      if (mappedBy != null) {
        // match using mappedBy as property name
        if (mappedBy.equalsIgnoreCase(prop.getName())) {
          return prop;
        }
      } else {
        // assume only one property that matches parent object type
        if (prop.getTargetType().equals(beanType)) {
          // found it, stop search
          return prop;
        }
      }
    }

    throw new RuntimeException("Can not find Master [" + beanType + "] in Child[" + targetDesc + "]");
  }

  /**
   * Search for and return the mapKey property.
   */
  private BeanProperty initMapKeyProperty() {

    // search for the property
    BeanDescriptor<?> targetDesc = getTargetDescriptor();
    for (BeanProperty prop : targetDesc.propertiesAll()) {
      if (mapKey.equalsIgnoreCase(prop.getName())) {
        return prop;
      }
    }

    String from = descriptor.getFullName();
    String to = targetDesc.getFullName();
    throw new PersistenceException(from + ": Could not find mapKey property [" + mapKey + "] on [" + to + "]");
  }

  public IntersectionRow buildManyDeleteChildren(EntityBean parentBean, List<Object> excludeDetailIds) {

    IntersectionRow row = new IntersectionRow(tableJoin.getTable(), targetDescriptor);
    if (excludeDetailIds != null && !excludeDetailIds.isEmpty()) {
      row.setExcludeIds(excludeDetailIds, getTargetDescriptor());
    }
    buildExport(row, parentBean);
    return row;
  }

  public IntersectionRow buildManyToManyDeleteChildren(EntityBean parentBean, boolean publish) {

    String tableName = publish ? intersectionPublishTable : intersectionDraftTable;
    IntersectionRow row = new IntersectionRow(tableName);
    buildExport(row, parentBean);
    return row;
  }

  public IntersectionRow buildManyToManyMapBean(EntityBean parent, EntityBean other, boolean publish) {

    String tableName = publish ? intersectionPublishTable : intersectionDraftTable;
    IntersectionRow row = new IntersectionRow(tableName);
    buildExport(row, parent);
    buildImport(row, other);
    return row;
  }

  /**
   * Register the mapping of intersection table to associated draft table.
   */
  public void registerDraftIntersectionTable(BeanDescriptorInitContext initContext) {
    if (hasDraftIntersection()) {
      initContext.addDraftIntersection(intersectionPublishTable, intersectionDraftTable);
    }
  }

  /**
   * Return true if the relationship is a ManyToMany with the intersection having an associated draft table.
   */
  private boolean hasDraftIntersection() {
    return intersectionDraftTable != null && !intersectionDraftTable.equals(intersectionPublishTable);
  }

  private void buildExport(IntersectionRow row, EntityBean parentBean) {

    if (embeddedExportedProperties) {
      BeanProperty idProp = descriptor.getIdProperty();
      parentBean = (EntityBean) idProp.getValue(parentBean);
    }
    for (ExportedProperty exportedProperty : exportedProperties) {
      Object val = exportedProperty.getValue(parentBean);
      String fkColumn = exportedProperty.getForeignDbColumn();

      row.put(fkColumn, val);
    }
  }

  /**
   * Set the predicates for lazy loading of the association.
   * Handles predicates for both OneToMany and ManyToMany.
   */
  private void buildImport(IntersectionRow row, EntityBean otherBean) {

    importedId.buildImport(row, otherBean);
  }

  /**
   * Return true if the otherBean has an Id value.
   */
  public boolean hasImportedId(EntityBean otherBean) {

    return null != targetDescriptor.getId(otherBean);
  }

  /**
   * Skip JSON write value for ToMany property.
   */
  @Override
  public void jsonWriteValue(SpiJsonWriter writeJson, Object value) {
    // do nothing, exclude ToMany properties
  }

  @Override
  public void jsonWrite(SpiJsonWriter ctx, EntityBean bean) throws IOException {
    if (!this.jsonSerialize) {
      return;
    }
    Boolean include = ctx.includeMany(name);
    if (Boolean.FALSE.equals(include)) {
      return;
    }

    Object value = getValueIntercept(bean);
    if (value != null) {
      ctx.pushParentBeanMany(bean);
      if (help != null) {
        help.jsonWrite(ctx, name, value, include != null);
      } else {
        if (isTransient && targetDescriptor == null) {
          ctx.writeValueUsingObjectMapper(name, value);
        } else {
          Collection<?> collection = (Collection<?>) value;
          if (!collection.isEmpty() || ctx.isIncludeEmpty()) {
            ctx.toJson(name, collection);
          }
        }
      }
      ctx.popParentBeanMany();
    }
  }

  @Override
  public void jsonRead(ReadJson readJson, EntityBean parentBean) throws IOException {
    jsonHelp.jsonRead(readJson, parentBean);
  }

  @SuppressWarnings("unchecked")
  public void publishMany(EntityBean draft, EntityBean live) {

    // collections will not be null due to enhancement
    BeanCollection<T> draftVal = (BeanCollection<T>) getValueIntercept(draft);
    BeanCollection<T> liveVal = (BeanCollection<T>) getValueIntercept(live);

    // Organise the existing live beans into map keyed by id
    Map<Object, T> liveBeansAsMap = liveBeansAsMap(liveVal);

    // publish from each draft to live bean creating new live beans as required
    draftVal.size();
    Collection<T> actualDetails = draftVal.getActualDetails();
    for (T bean : actualDetails) {
      Object id = targetDescriptor.getId((EntityBean) bean);
      T liveBean = liveBeansAsMap.remove(id);

      if (isManyToMany()) {
        if (liveBean == null) {
          // add new relationship (Map not allowed here)
          liveVal.addBean(targetDescriptor.createReference(id, null));
        }

      } else {
        // recursively publish the OneToMany child bean
        T newLive = targetDescriptor.publish(bean, liveBean);
        if (liveBean == null) {
          // Map not allowed here
          liveVal.addBean(newLive);
        }
      }
    }

    // anything remaining should be deleted (so remove from modify aware collection)
    Collection<T> values = liveBeansAsMap.values();
    for (T value : values) {
      liveVal.removeBean(value);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<Object, T> liveBeansAsMap(BeanCollection<?> liveVal) {

    liveVal.size();
    Collection<?> liveBeans = liveVal.getActualDetails();
    Map<Object, T> liveMap = new LinkedHashMap<>();

    for (Object liveBean : liveBeans) {
      Object id = targetDescriptor.getId((EntityBean) liveBean);
      liveMap.put(id, (T) liveBean);
    }
    return liveMap;
  }

  public boolean isIncludeCascadeSave() {
    // Note ManyToMany always included as we always 'save'
    // the relationship via insert/delete of intersection table
    // REMOVALS means including PrivateOwned relationships
    return cascadeInfo.isSave() || hasJoinTable() || ModifyListenMode.REMOVALS == modifyListenMode;
  }

  public boolean isIncludeCascadeDelete() {
    return cascadeInfo.isDelete() || o2mJoinTable || ModifyListenMode.REMOVALS == modifyListenMode;
  }

  public String insertElementCollection() {
    return sqlHelp.insertElementCollection();
  }

  public boolean isTargetDocStoreMapped() {
    return targetDescriptor.isDocStoreMapped();
  }

  public BeanCollectionHelp<T> getHelp() {
    return help;
  }

  public void jsonWriteElementValue(SpiJsonWriter ctx, Object element) {
    throw new IllegalStateException("Never Expected");
  }

  /**
   * Read the collection (JSON Array) containing entity beans.
   */
  public Object jsonReadCollection(ReadJson readJson, EntityBean parentBean) throws IOException {
    BeanCollection<?> collection = createEmpty(parentBean);
    BeanCollectionAdd add = getBeanCollectionAdd(collection, null);
    do {
      EntityBean detailBean = (EntityBean) targetDescriptor.jsonRead(readJson, name);
      if (detailBean == null) {
        // read the entire array
        break;
      }
      add.addEntityBean(detailBean);

      if (parentBean != null && childMasterProperty != null) {
        // bind detail bean back to master via mappedBy property
        childMasterProperty.setValue(detailBean, parentBean);
      }
    } while (true);

    return collection;
  }
}
