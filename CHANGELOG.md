# Change Log
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0-alpha-7] - 2024.10.18

### Added

- Add `get-contexts-by-selectors` to get a subset of the existing contexts.

## [0.1.0-alpha-6] - 2024.10.01

### Fixed

- Pre-populate clj-kondo imports, to avoid false positives

## [0.1.0-alpha-5] - 2024.10.01

### Fixed
- The `db-spec` spec only accepted a subset of the valid "connectable" things that next.jdbc accepts. As we started adding pre-conditionchecks for the validity of the db-spec arguments to several functions, this resulted in assertions being thrown when using other kinds of "connectables".

## [0.1.0-alpha-4] - 2024.09.16

### Changed

- Updated leiningen and clj-kondo to latest stable versions.
- Updated suggested Postgresql table definitions to include ON CASCADE DELETE for the cases where it makes sense.
- Add constraints to the recommended Postgresql tables definitions, to prevent cycles in the context child-parent relationship. Otherwise, when checking permissions, we can get infinite loops.

## [0.1.0-alpha-3] - 2024.09.11

### Changed
- Updated dependencies to latest stable versions.
- Catch SQL exceptions due to constraint violations locally, instead
  of letting them bubble up to the application.
- **[BREAKING CHANGE]** Use namespaced keywords to grant and deny permissions.
- **[BREAKING CHANGE]** The context hierarchy model has
  changed. Previously it was implemented as a regular tree, and a given
  context could only have a single parent context (ancestor), and there
  was a *single* root context (represented by a context whose parent
  context was `nil`). Now the implementation is a full Directed Acyclic
  Graph (DAG), where each context can have zero or more parent
  contents. This means that we can also have multiple independent root
  contexts (contexts that don't have *any* ancestors).

  As a consequence, the underlying database model has changed. A new
  table is used (`rbac_context_parent`), and the parent-child
  relationship information needs to be moved from the `rbac_context`
  table to the new `rbac_context_parent` table. Something like the
  following SQL queries should be enough:

  ```
  BEGIN;
  -- ;;
  CREATE TABLE IF NOT EXISTS rbac_context_parent
    (child_id UUID NOT NULL,
     parent_id UUID NOT NULL,
     PRIMARY KEY (child_id, parent_id),
     CONSTRAINT rbac_context_parent_child_id_fk FOREIGN KEY(child_id) REFERENCES rbac_context(id),
     CONSTRAINT rbac_context_parent_parent_id_fk FOREIGN KEY(parent_id) REFERENCES rbac_context(id));
  -- ;;
  INSERT INTO rbac_context_parent (child_id, parent_id)
    (SELECT
         id AS child_id,
         parent AS parent_id
     FROM
         rbac_context
     WHERE parent IS NOT NULL);
  -- ;;
  ALTER TABLE rbac_context DROP COLUMN parent;
  -- ;;
  COMMIT;
  ```

## [0.1.0-alpha-2] - 2023.04.24

### Changed

- Rename `has-permission` to `has-permission?` to better convey the fact that this is a predicate function.

## [0.1.0-alpha-1] - 2023.04.24

- First public release. This is alpha quality software.

[Unreleased]: https://github.com/gethop-dev/rbac.next/compare/v0.1.0.alpha-3...main
[0.1.0-alpha-3]: https://github.com/gethop-dev/rbac.next/compare/v0.1.0-alpha-2...v0.1.0-alpha-3
[0.1.0-alpha-2]: https://github.com/gethop-dev/rbac.next/compare/v0.1.0-alpha-1...v0.1.0-alpha-2
[0.1.0-alpha-1]: https://github.com/gethop-dev/rbac.next/releases/tag/v0.1.0.alpha-1
