/*
 * Copyright 2020 The Embulk project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.embulk.config;

/**
 * Represents a difference in configurations.
 *
 * <p>It had two following methods that return Jackson's instances till v0.9, but they are removed so that
 * Jackson on the core side can be hidden from plugins.
 *
 * <ul>
 * <li>{@code Iterable<Map.Entry<String, JsonNode>> getAttributes()}
 * <li>{@code ObjectNode getObjectNode()}
 * </ul>
 */
public interface ConfigDiff extends DataSource {
    /**
     * Returns a nested value under {@code attrName}.
     *
     * @param attrName  name of the nested attribute to look for
     * @return the nested attribute value of {@code attrName}
     */
    @Override
    ConfigDiff getNested(String attrName);

    /**
     * Returns a nested value under {@code attrName}. Sets an empty object node {@code "{}"} there if it is empty.
     *
     * @param attrName  name of the nested attribute to look for
     * @return the nested attribute value of {@code attrName}
     */
    @Override
    ConfigDiff getNestedOrSetEmpty(String attrName);

    /**
     * Returns a nested value under {@code attrName}. Returns an empty object node {@code "{}"} there if it is empty.
     *
     * @param attrName  name of the nested attribute to look for
     * @return the nested attribute value of {@code attrName}
     */
    @Override
    ConfigDiff getNestedOrGetEmpty(String attrName);

    /**
     * Sets a value for {@code attrName}.
     *
     * @param attrName  name of the attribute to set the value at
     * @param v  the value {@link java.lang.Object} to set
     * @return itself after the value is set
     */
    @Override
    ConfigDiff set(String attrName, Object v);

    /**
     * Sets a nested value for {@code attrName}.
     *
     * @param attrName  name of the attribute to set the nested value at
     * @param v  the nested value to set
     * @return itself after the nested value is set
     */
    @Override
    ConfigDiff setNested(String attrName, DataSource v);

    /**
     * Sets all attributes in {@code other} into itself.
     *
     * @param other  the other {@link org.embulk.config.ConfigDiff} to set
     * @return itself after the attributes are set
     */
    @Override
    ConfigDiff setAll(DataSource other);

    /**
     * Removes the attribute named {@code attrName}.
     *
     * @param attrName  name of the attribute to remove
     * @return itself after the attribute is removed
     */
    @Override
    ConfigDiff remove(String attrName);

    /**
     * Creates a deep copy of itself.
     *
     * @return the new {@link org.embulk.config.ConfigDiff} instance that is deep-copied from itself
     */
    @Override
    ConfigDiff deepCopy();

    /**
     * Merges another {@link org.embulk.config.ConfigDiff} into itself.
     *
     * @param other  the other {@link org.embulk.config.ConfigDiff} to merge
     * @return itself after the other {@link org.embulk.config.ConfigDiff} is merged
     */
    @Override
    ConfigDiff merge(DataSource other);
}
