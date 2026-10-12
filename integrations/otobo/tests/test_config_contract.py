import unittest
from pathlib import Path
import xml.etree.ElementTree as ET


class ConfigContractTest(unittest.TestCase):
    def test_otobo_value_types_and_disabled_default(self):
        source = Path(__file__).parents[1] / "Custom/Kernel/Config/Files/XML/MissionLebenPush.xml"
        settings = {element.attrib["Name"]: element for element in ET.parse(source).getroot()}
        self.assertEqual(4, len(settings))
        enabled = settings["MissionLebenPush::Enabled"].find("Value/Item")
        self.assertIsNotNone(enabled)
        self.assertEqual("Checkbox", enabled.attrib["ValueType"])
        self.assertEqual("0", enabled.text)
        for name in ("MissionLebenPush::URL", "MissionLebenPush::SecretFile"):
            self.assertEqual("String", settings[name].find("Value/Item").attrib["ValueType"])

    def test_exact_post_transaction_event(self):
        source = Path(__file__).parents[1] / "Custom/Kernel/Config/Files/XML/MissionLebenPush.xml"
        settings = {element.attrib["Name"]: element for element in ET.parse(source).getroot()}
        event = settings["Ticket::EventModulePost###9900-MissionLebenPush"]
        values = {element.attrib["Key"]: element.text for element in event.findall("Value/Hash/Item")}
        self.assertEqual({"Event": "ArticleCreate", "Module": "Kernel::System::Ticket::Event::MissionLebenPush", "Transaction": "1"}, values)


if __name__ == "__main__":
    unittest.main()
