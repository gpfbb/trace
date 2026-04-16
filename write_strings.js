const fs = require("fs");
const content = `<resources>
    <string name="app_name">TrackMaster</string>
</resources>`;
fs.writeFileSync("D:/trace/app/src/main/res/values/strings.xml", content);
console.log("done");
