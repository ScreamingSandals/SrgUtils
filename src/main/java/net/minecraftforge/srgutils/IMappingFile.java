/*
 * SRG Utils
 * Copyright (c) 2021
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.srgutils;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

public interface IMappingFile {
    static IMappingFile load(File path) throws IOException {
        return load(path, false);
    }

    static IMappingFile load(File path, boolean reverseClass) throws IOException {
        try (InputStream in = new FileInputStream(path)) {
            return load(in, reverseClass);
        }
    }

    static IMappingFile load(InputStream in) throws IOException {
        return load(in, false);
    }

    static IMappingFile load(InputStream in, boolean reverseClass) throws IOException {
        return InternalUtils.load(in, reverseClass);
    }

    static IMappingFile load(String mappingFile) throws IOException {
        return load(mappingFile, false);
    }

    static IMappingFile load(String mappingFile, boolean reverseClass) throws IOException {
        return load(new ByteArrayInputStream(mappingFile.getBytes(StandardCharsets.UTF_8)), reverseClass);
    }

    static IMappingFile load(String[] mappingFile) throws IOException {
        return load(mappingFile, false);
    }

    static IMappingFile load(String[] mappingFile, boolean reverseClass) throws IOException {
        return load(String.join("\n", mappingFile));
    }

    /**
     * Gets all packages contained in this mapping file.
     *
     * @return all packages
     */
    Collection<? extends IPackage> getPackages();

    /**
     * Gets a package contained in this mapping file by its original name.
     *
     * @param original the original package name
     * @return the package, null if not found
     */
    @Nullable
    IPackage getPackage(String original);

    /**
     * Gets all classes contained in this mapping file.
     *
     * @return all classes
     */
    Collection<? extends IClass> getClasses();

    /**
     * Gets a class contained in this mapping file by its original name.
     *
     * @param original the original class name
     * @return the class, null if not found
     */
    @Nullable
    IClass getClass(String original);

    /**
     * Gets a class contained in this mapping file by its mapped name.
     *
     * @param mapped the mapped class name
     * @return the class, null if not found
     */
    @Nullable
    IClass getMappedClass(String mapped);

    /**
     * Remaps an original package name to its mapping.
     *
     * @param pkg the original package name
     * @return the remapped package name
     */
    String remapPackage(String pkg);

    /**
     * Remaps an original class name to its mapping.
     *
     * @param desc the original class name
     * @return the remapped class name
     */
    String remapClass(String desc);

    /**
     * Remaps an original descriptor to its mapping.
     *
     * @param desc the original descriptor
     * @return the remapped descriptor
     */
    String remapDescriptor(String desc);

    /**
     * Writes this mapping file to a file with the specified {@link Format}.
     *
     * @param path the path
     * @param format the mapping file format
     * @param reversed should the written mapping file be reversed?
     * @throws IOException when an IO error occurs
     */
    void write(Path path, Format format, boolean reversed) throws IOException;

    /**
     * Reverses this mapping file.
     * The reversing strategy is simply that A->B becomes B->A.
     *
     * @return the reversed {@link IMappingFile}
     */
    IMappingFile reverse();

    IMappingFile rename(IRenamer renamer);

    IMappingFile chain(IMappingFile other);

    /**
     * Merge this mapping file with another one.
     * <p>
     * The merge strategy used for chaining is as follows:
     * <p>
     * A->B ("ours") and B->C ("theirs") becomes A->C. Mappings present in only ours are preserved,
     * but mappings present only in theirs are dropped, except for parameters, which are preserved for all method mappings
     * that were present in ours.
     * Conflicts in metadata are resolved by overwriting with the B->C metadata.
     *
     * @param other the other mapping file
     * @return the merged {@link IMappingFile}
     */
    IMappingFile merge(IMappingFile other);

    /**
     * Computes a class name diff between this mapping file and another one.
     * <p>
     * The behavior of this method can be changed with the 'diffThis' parameter:
     * <ul>
     *     <li>
     *         If true, the returned map consists of the A->B mappings
     *         from <strong>this</strong> mapping file which were not found in <strong>the other</strong> mapping file.
     *     </li>
     *     <li>
     *         If false, the returned map consists of the A->B mappings
     *         from <strong>the other</strong> mapping file which were not found in <strong>this</strong> mapping file.
     *     </li>
     * </ul>
     *
     * @param other the other mapping file
     * @param diffThis the diff setting
     * @return the diff
     */
    Map<String, String> diff(IMappingFile other, boolean diffThis);

    enum Format {
        SRG(false, false, false),
        XSRG(false, true, false),
        CSRG(false, false, false),
        TSRG(true, false, false),
        TSRG2(true, true, true),
        PG(true, true, false),
        TINY1(false, true, true),
        TINY(true, true, false);

        private final boolean ordered;
        private final boolean hasFieldTypes;
        private final boolean hasNames;

        Format(boolean ordered, boolean hasFieldTypes, boolean hasNames) {
            this.ordered = ordered;
            this.hasFieldTypes = hasFieldTypes;
            this.hasNames = hasNames;
        }

        public static Format get(String name) {
            name = name.toUpperCase(Locale.ENGLISH);
            for (Format value : values())
                if (value.name().equals(name))
                    return value;
            return null;
        }

        public boolean isOrdered() {
            return this.ordered;
        }

        public boolean hasFieldTypes() {
            return this.hasFieldTypes;
        }

        public boolean hasNames() {
            return this.hasNames;
        }
    }

    interface INode {
        String getOriginal();

        String getMapped();

        @Nullable // Returns null if the specified format doesn't support this node type
        String write(Format format, boolean reversed);

        /*
         * An unmodifiable map of various metadata that is attached to this node.
         * This is very dependent on the format. Some examples:
         * Tiny v1/v2:
         *   "comment": Javadoc comment to insert into source
         * TSRG:
         *   On Methods:
         *     "is_static": Value means nothing, just a marker if it exists.
         * Proguard:
         *   On Methods:
         *     "start_line": The source line that this method starts on
         *     "end_line": The source line for the end of this method
         */
        Map<String, String> getMetadata();
    }

    interface IOwnedNode<T> extends INode {
        T getParent();
    }

    interface IPackage extends IOwnedNode<IMappingFile> {
    }

    interface IClass extends IOwnedNode<IMappingFile> {
        Collection<? extends IField> getFields();

        Collection<? extends IMethod> getMethods();

        String remapField(String field);

        String remapMethod(String name, String desc);

        @Nullable
        IField getField(String name);

        @Nullable
        IField getMappedField(String name);

        @Nullable
        IMethod getMethod(String name, String desc);

        @Nullable
        IMethod getMappedMethod(String name, String desc);

        /**
         * Computes a method name diff between this mapping file and another one.
         * <p>
         * The behavior of this method can be changed with the 'diffThis' parameter:
         * <ul>
         *     <li>
         *         If true, the returned map consists of the A->B mappings
         *         from <strong>this</strong> class mapping which were not found in <strong>the other</strong> class mapping.
         *     </li>
         *     <li>
         *         If false, the returned map consists of the A->B mappings
         *         from <strong>the other</strong> class mapping which were not found in <strong>this</strong> class mapping.
         *     </li>
         * </ul>
         *
         * @param other the other mapping file
         * @param diffThis the diff setting
         * @return the diff
         */
        Map<String, String> diffMethods(IClass other, boolean diffThis);

        /**
         * Computes a field name diff between this mapping file and another one.
         * <p>
         * The behavior of this method can be changed with the 'diffThis' parameter:
         * <ul>
         *     <li>
         *         If true, the returned map consists of the A->B mappings
         *         from <strong>this</strong> class mapping which were not found in <strong>the other</strong> class mapping.
         *     </li>
         *     <li>
         *         If false, the returned map consists of the A->B mappings
         *         from <strong>the other</strong> class mapping which were not found in <strong>this</strong> class mapping.
         *     </li>
         * </ul>
         *
         * @param other the other mapping file
         * @param diffThis the diff setting
         * @return the diff
         */
        Map<String, String> diffFields(IClass other, boolean diffThis);
    }

    interface IField extends IOwnedNode<IClass> {
        @Nullable
        String getDescriptor();

        @Nullable
        String getMappedDescriptor();
    }

    interface IMethod extends IOwnedNode<IClass> {
        String getDescriptor();

        String getMappedDescriptor();

        Collection<? extends IParameter> getParameters();

        String remapParameter(int index, String name);
    }

    interface IParameter extends IOwnedNode<IMethod> {
        int getIndex();
    }
}
