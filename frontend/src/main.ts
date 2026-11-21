import { createApp } from "vue";
import App from "./App.vue";
import "./style.css";
import "./atoms-theme.css";
import "./task-workbench.css";
if (import.meta.env.DEV && new URLSearchParams(location.search).get("prototype") === "task-home") {
  void import("./TaskHomePrototype.vue").then(({ default: Prototype }) => createApp(Prototype).mount("#app"));
} else if (import.meta.env.DEV && new URLSearchParams(location.search).get("prototype") === "workbench") {
  void import("./WorkbenchPrototype.vue").then(({ default: Prototype }) => createApp(Prototype).mount("#app"));
} else {
  createApp(App).mount("#app");
}
