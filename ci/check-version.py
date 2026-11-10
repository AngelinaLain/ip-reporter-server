"""发布标签必须和后端项目版本一致，避免部署引用不存在的版本。"""
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET

namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
version = ET.parse('server/pom.xml').findtext('m:version', namespaces=namespace)
assert re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version or '')
if os.environ.get('GITHUB_REF_TYPE') == 'tag':
    assert os.environ['GITHUB_REF_NAME'] == 'v' + version, 'Tag must equal v + server/pom.xml version'
    for name in ['.env.example', 'scripts/init-server.sh', 'scripts/init-server.ps1']:
        content = Path(name).read_text(encoding='utf-8-sig')
        assert 'IMAGE_TAG=' + version in content, f'{name} must default to the released version'
    assert '${IMAGE_TAG:-' + version + '}' in Path('compose.yaml').read_text(), 'Compose default must equal release version'
print('Server version:', version)
