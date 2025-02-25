/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.storage;

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

class HostedRepositoryStorage<T> implements Storage<T> {
    private final HostedRepository hostedRepository;
    private final String ref;
    private final String fileName;
    private final String authorName;
    private final String authorEmail;
    private final String message;
    private final StorageSerializer<T> serializer;
    private final StorageDeserializer<T> deserializer;
    private final Repository localRepository;

    private RepositoryStorage<T> repositoryStorage;
    private Set<T> current;
    private final static Logger log = Logger.getLogger("org.openjdk.skara.storage");

    HostedRepositoryStorage(HostedRepository repository, Path localStorage, String ref, String fileName, String authorName, String authorEmail, String message, StorageSerializer<T> serializer, StorageDeserializer<T> deserializer) {
        this.hostedRepository = repository;
        this.ref = ref;
        this.fileName = fileName;
        this.authorEmail = authorEmail;
        this.authorName = authorName;
        this.message = message;
        this.serializer = serializer;
        this.deserializer = deserializer;

        localRepository = tryMaterialize(repository, localStorage, ref, fileName, authorName, authorEmail, message);
        repositoryStorage = new RepositoryStorage<>(localRepository, fileName, authorName, authorEmail, message, serializer, deserializer);
        current = current();
    }

    private static Repository tryMaterialize(HostedRepository repository, Path localStorage, String ref, String fileName, String authorName, String authorEmail, String message) {
        int retryCount = 0;
        IOException lastException = null;

        while (retryCount < 10) {
            try {
                try {
                    return Repository.materialize(localStorage, repository.authenticatedUrl(), "+" + ref + ":storage");
                } catch (IOException e2) {
                    // The remote ref may not yet exist
                    Repository localRepository = Repository.init(localStorage, repository.repositoryType());
                    if (!localRepository.isEmpty()) {
                        // If the materialization failed but the local repository already contains data, do not initialize the ref
                        log.log(Level.WARNING, "Materialization into existing local repository failed", e2);
                        lastException = e2;
                        retryCount++;
                        continue;
                    }

                    log.info("Creating initial storage for: " + ref);
                    var file = localStorage.resolve(fileName);
                    Files.createDirectories(file.getParent());
                    var storage = Files.writeString(localStorage.resolve(fileName), "");
                    localRepository.add(storage);
                    var firstCommit = localRepository.commit(message, authorName, authorEmail);

                    // If the materialization failed for any other reason than the remote ref not existing, this will fail
                    localRepository.push(firstCommit, repository.authenticatedUrl(), ref);
                    return localRepository;
                }
            } catch (IOException e) {
                lastException = e;
            }
            retryCount++;
        }
        throw new UncheckedIOException("Retry count exceeded", lastException);
    }

    @Override
    public Set<T> current() {
        return repositoryStorage.current();
    }

    @Override
    public void put(Collection<T> items) {
        int retryCount = 0;
        IOException lastException = null;
        Hash lastRemoteHash = null;

        while (retryCount < 10) {
            // Update our local storage
            repositoryStorage.put(items);
            var updated = repositoryStorage.current();
            if (current.equals(updated)) {
                return;
            }

            // The local storage has changed, try to push it to the remote
            try {
                var updatedHash = localRepository.head();
                localRepository.push(updatedHash, hostedRepository.authenticatedUrl(), ref);
                current = updated;
                return;
            } catch (IOException e) {
                lastException = e;

                // Check if the remote has changed
                try {
                    var remoteHash = localRepository.fetch(hostedRepository.authenticatedUrl(), ref);
                    if (!remoteHash.equals(lastRemoteHash)) {
                        localRepository.checkout(remoteHash, true);
                        repositoryStorage = new RepositoryStorage<>(localRepository, fileName, authorName, authorEmail, message, serializer, deserializer);
                        lastRemoteHash = remoteHash;

                        // We are making progress catching up with remote changes, don't update the retryCount
                        continue;
                    }
                } catch (IOException e1) {
                    lastException = e1;
                }
                retryCount++;
            }
        }

        throw new UncheckedIOException("Retry count exceeded", lastException);
    }
}
