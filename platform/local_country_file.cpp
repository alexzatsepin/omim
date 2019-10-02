#include "platform/local_country_file.hpp"

#include "platform/mwm_version.hpp"
#include "platform/platform.hpp"

#include "coding/internal/file_data.hpp"
#include "coding/sha1.hpp"

#include "base/assert.hpp"
#include "base/file_name_utils.hpp"
#include "base/logging.hpp"
#include "base/stl_helpers.hpp"

#include <algorithm>
#include <sstream>

using namespace std;

namespace platform
{
LocalCountryFile::LocalCountryFile()
    : m_version(0)
{
}

LocalCountryFile::LocalCountryFile(string const & directory, CountryFile const & countryFile,
                                   int64_t version)
    : m_directory(directory),
      m_countryFile(countryFile),
      m_version(version)
{
}

void LocalCountryFile::SyncWithDisk()
{
  m_files = {};
  uint64_t size = 0;
  Platform & platform = GetPlatform();

  // Now we are not working with several files at the same time and diffs have greater priority.
  for (MapOptions type : {MapOptions::Diff, MapOptions::Map})
  {
    ASSERT_LESS(base::Underlying(type), m_files.size(), ());

    if (platform.GetFileSizeByFullPath(GetPath(type), size))
    {
      m_files[base::Underlying(type)] = size;
      break;
    }
  }
}

void LocalCountryFile::DeleteFromDisk(MapOptions type) const
{
  ASSERT_LESS(base::Underlying(type), m_files.size(), ());

  if (!OnDisk(type))
    return;

  if (!base::DeleteFileX(GetPath(type)))
    LOG(LERROR, (type, "from", *this, "wasn't deleted from disk."));
}

string LocalCountryFile::GetPath(MapOptions type) const
{
  return base::JoinPath(m_directory, GetFileName(m_countryFile.GetName(), type, GetVersion()));
}

uint64_t LocalCountryFile::GetSize(MapOptions type) const
{
  ASSERT_LESS(base::Underlying(type), m_files.size(), ());

  return m_files[base::Underlying(type)];
}

bool LocalCountryFile::HasFiles() const
{
  return std::any_of(m_files.cbegin(), m_files.cend(), [](auto value) { return value != 0; });
}

bool LocalCountryFile::OnDisk(MapOptions type) const
{
  ASSERT_LESS(base::Underlying(type), m_files.size(), ());

  return m_files[base::Underlying(type)] != 0;
}

bool LocalCountryFile::operator<(LocalCountryFile const & rhs) const
{
  if (m_countryFile != rhs.m_countryFile)
    return m_countryFile < rhs.m_countryFile;
  if (m_version != rhs.m_version)
    return m_version < rhs.m_version;
  if (m_directory != rhs.m_directory)
    return m_directory < rhs.m_directory;
  if (m_files != rhs.m_files)
    return m_files < rhs.m_files;
  return false;
}

bool LocalCountryFile::operator==(LocalCountryFile const & rhs) const
{
  return m_directory == rhs.m_directory && m_countryFile == rhs.m_countryFile &&
         m_version == rhs.m_version && m_files == rhs.m_files;
}

bool LocalCountryFile::ValidateIntegrity() const
{
  auto calculatedSha1 = coding::SHA1::CalculateBase64(GetPath(MapOptions::Map));
  ASSERT_EQUAL(calculatedSha1, m_countryFile.GetSha1(), ("Integrity failure"));
  return calculatedSha1 == m_countryFile.GetSha1();
}

// static
LocalCountryFile LocalCountryFile::MakeForTesting(string const & countryFileName, int64_t version)
{
  CountryFile const countryFile(countryFileName);
  LocalCountryFile localFile(GetPlatform().WritableDir(), countryFile, version);
  localFile.SyncWithDisk();
  return localFile;
}

// static
LocalCountryFile LocalCountryFile::MakeTemporary(string const & fullPath)
{
  string name = fullPath;
  base::GetNameFromFullPath(name);
  base::GetNameWithoutExt(name);

  return LocalCountryFile(base::GetDirectory(fullPath), CountryFile(name), 0 /* version */);
}

string DebugPrint(LocalCountryFile const & file)
{
  ostringstream os;
  os << "LocalCountryFile [" << file.m_directory << ", " << DebugPrint(file.m_countryFile) << ", "
     << file.m_version << ", " << ::DebugPrint(file.m_files) << "]";
  return os.str();
}
}  // namespace platform
