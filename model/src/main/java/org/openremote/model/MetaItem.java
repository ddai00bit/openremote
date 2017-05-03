/*
 * Copyright 2016, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.model;

import elemental.json.Json;
import elemental.json.JsonObject;
import elemental.json.JsonValue;
import org.openremote.model.util.JsonUtil;
import org.openremote.model.util.TextUtil;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import static org.openremote.model.util.JsonUtil.sanitizeJsonValue;

/**
 * A named arbitrary {@link JsonValue}.
 * <p>
 * Name should be a URI, thus avoiding collisions and representing "ownership" of the meta item.
 */
public class MetaItem extends AbstractValueHolder {

    public MetaItem() {
        this(Json.createObject());
    }

    public MetaItem(JsonObject jsonObject) {
        super(jsonObject);
    }

    public MetaItem(String name) {
        this(name, null);
    }

    public MetaItem(String name, JsonValue value) {
        super(Json.createObject());
        setName(name);
        setValue(value);
    }

    public MetaItem(HasMetaName hasMetaName, JsonValue value) {
        this(hasMetaName.getUrn(), value);
    }

    public boolean hasName() {
        return getName().isPresent();
    }

    public Optional<String> getName() {
        if (jsonObject == null) {
            return Optional.empty();
        }

        return JsonUtil
            .asString((JsonValue)jsonObject.get("name"))
            .map(str -> str.isEmpty() ? null : str);
    }

    public void setName(String name) {
        TextUtil.requireNonNullAndNonEmpty(name);
        jsonObject.put("name", name);
    }

    @Override
    public boolean isValid() {
        return super.isValid() && hasName();
    }

    public MetaItem copy() {
        return new MetaItem(Json.parse(getJsonObject().toJson()));
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), getValue());
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof MetaItem)) {
            return false;
        }

        MetaItem metaItem = (MetaItem) o;
        return Objects.equals(getName(), metaItem.getName()) &&
            JsonUtil.equals(getValue().orElse(null), metaItem.getValue().orElse(null));
    }

    @Override
    public String toString() {
        return "MetaItem{" +
            jsonObject.toJson() +
            '}';
    }

//    ---------------------------------------------------
//    FUNCTIONAL METHODS BELOW
//    ---------------------------------------------------

    public static boolean equals(MetaItem item1, MetaItem item2) {
        if (item1 == null && item2 == null)
            return true;

        if (item1 == null || item2 == null)
            return false;

        Optional<String> name = item2.getName();
        return isMetaNameEqualTo(item1, name.orElse(null)) && JsonUtil.equals(item1.getValue().orElse(null), item2.getValue().orElse(null));
    }

    public static boolean isMetaNameEqualTo(MetaItem item, String name) {
        if (item == null)
            return false;

        Optional<String> itemName = item.getName();
        return (!itemName.isPresent() && name == null) || (itemName.isPresent() && itemName.get().equals(name));
    }

    public static Predicate<MetaItem> isMetaNameEqualTo(String name) {
        return metaItem -> isMetaNameEqualTo(metaItem, name);
    }

    public static boolean isMetaNameEqualTo(MetaItem item, HasMetaName hasMetaName) {
        return hasMetaName != null && isMetaNameEqualTo(item, hasMetaName.getUrn());
    }

    public static Predicate<MetaItem> isMetaNameEqualTo(HasMetaName hasMetaName) {
        return metaItem -> isMetaNameEqualTo(metaItem, hasMetaName);
    }

// STREAM AND COLLECTION METHODS BELOW

    public static <T extends Collection<MetaItem>> void replaceMetaByName(T metaItems, String name, MetaItem newMetaItem) {
        metaItems.removeIf(isMetaNameEqualTo(name));
        metaItems.add(newMetaItem);
    }

    public static <T extends Collection<MetaItem>> void replaceMetaByName(T metaItems, String name, JsonValue newValue) {
        metaItems.removeIf(isMetaNameEqualTo(name));
        metaItems.add(new MetaItem(name, newValue));
    }

    public static <T extends Collection<MetaItem>> void replaceMetaByName(T metaItems, String name, T newMetaItems) {
        metaItems.removeIf(isMetaNameEqualTo(name));
        metaItems.addAll(newMetaItems);
    }

    public static <T extends Collection<MetaItem>, V extends Collection<JsonValue>> void replaceMetaByNameWithValues(T metaItems, String name, V newValues) {
        metaItems.removeIf(isMetaNameEqualTo(name));
        newValues
            .stream()
            .map(value -> new MetaItem(name, value))
            .forEach(metaItems::add);
    }

    public static <T extends Collection<MetaItem>> void replaceMetaByName(T metaItems, HasMetaName hasMetaName, MetaItem newMetaItem) {
        replaceMetaByName(metaItems, hasMetaName.getUrn(), newMetaItem);
    }

    public static <T extends Collection<MetaItem>> void replaceMetaByName(T metaItems, HasMetaName hasMetaName, JsonValue newValue) {
        replaceMetaByName(metaItems, hasMetaName.getUrn(), newValue);
    }

    public static <T extends Collection<MetaItem>> void replaceMetaByName(T metaItems, HasMetaName hasMetaName, T newMetaItems) {
        replaceMetaByName(metaItems, hasMetaName.getUrn(), newMetaItems);
    }

    public static <T extends Collection<MetaItem>, V extends Collection<JsonValue>> void replaceMetaByNameWithValues(T metaItems, HasMetaName hasMetaName, V newValues) {
        replaceMetaByNameWithValues(metaItems, hasMetaName.getUrn(), newValues);
    }
}