import "@testing-library/jest-dom/vitest";

// jsdom has no layout engine; ag-grid + monaco are mocked in the App test, but
// a couple of browser globals they/React lean on aren't implemented. Shim the
// ones our components touch so a render doesn't throw.
if (!window.matchMedia) {
  // @ts-expect-error test shim
  window.matchMedia = () => ({
    matches: false,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
  });
}
